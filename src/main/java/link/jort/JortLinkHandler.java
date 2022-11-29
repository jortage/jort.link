package link.jort;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Iterables;
import com.google.common.escape.Escaper;
import com.google.common.escape.Escapers;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import com.google.common.io.CountingInputStream;
import com.google.common.io.CountingOutputStream;
import com.google.common.io.MoreFiles;
import com.google.common.net.InternetDomainName;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static link.jort.JortLink.http;

public final class JortLinkHandler extends AbstractHandler {
	private static final Logger log = LoggerFactory.getLogger(JortLinkHandler.class);

	private static final long M = 1024*1024;
	
	record RequestResult(ByteSource data, String contentType, boolean cached, int status, String message) {
		
		public RequestResult(ByteSource data, String contentType, boolean cached, int status) {
			this(data, contentType, cached, status, null);
		}
		
		public RequestResult(int status, String message) {
			this(null, null, false, status, message);
		}
		
		public RequestResult withCached() {
			return new RequestResult(data, contentType, true, status, message);
		}
	}

	private final Map<String, CompletableFuture<RequestResult>> futures = new HashMap<>();
	private final Cache<String, RequestResult> pasts = CacheBuilder.newBuilder()
			.expireAfterAccess(2, TimeUnit.HOURS)
			.maximumSize(1024)
			.softValues()
			.build();
	
	private static final Splitter ACCEPT_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();
	
	private static final Splitter SLASH_SPLITTER2 = Splitter.on('/').limit(2).omitEmptyStrings();
	private static final Splitter SLASH_SPLITTER3 = Splitter.on('/').limit(3).omitEmptyStrings();
	
	private static final Pattern CONTENT_TYPE_PATTERN = Pattern.compile("^([^;]*)(?:$|;\\s*(?:charset=(.*))?)");
	
	private static final Escaper LINK_ESCAPER = Escapers.builder()
			.addEscape('<', "%3C")
			.addEscape('>', "%3E")
			.build();

	@Override
	public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
		baseRequest.setHandled(true);
		Host host = Host.of(request.getHeader("Host"));
		if (host == null) {
			response.sendError(421);
			return;
		}
		switch (request.getMethod()) {
			case "OPTIONS":
				response.setHeader("Allow", "GET, HEAD, OPTIONS");
				response.setStatus(204);
				response.getOutputStream().close();
				return;
			case "GET":
			case "HEAD":
				break;
			default:
				response.sendError(405);
				return;
		}
		String ua = request.getHeader("User-Agent");
		boolean fedi = false;
		for (var p : JortLink.uaPatterns) {
			if (p.matcher(ua).find()) {
				fedi = true;
				break;
			}
		}
		String tgtHost = null;
		response.setHeader("Vary", "User-Agent");
		Iterator<String> split;
		Host effectiveHost = host;
		if (host.cache()) {
			split = SLASH_SPLITTER3.split(target).iterator();
			if (!split.hasNext()) {
				sendRedirect(response, 307, http+"://"+Host.FRONT);
				return;
			}
			var en = split.next();
			effectiveHost = Host.of(en);
			if (effectiveHost == null) {
				effectiveHost = Host.FRONT;
				tgtHost = en;
			}
		} else {
			split = SLASH_SPLITTER2.split(target).iterator();
		}
		if (tgtHost == null) {
			if (!split.hasNext()) {
				sendRedirect(response, 301, http+"://"+Host.FRONT);
				return;
			}
			tgtHost = split.next();
		}
		if (fedi && !host.cache()) {
			sendRedirect(response, 307, http+"://"+Host.CACHE+"/"+host+target);
			return;
		}
		if (fedi && effectiveHost.exclude()) {
			response.setHeader("Cache-Control", "public, max-age=86400");
			response.setStatus(204);
			response.getOutputStream().close();
			return;
		}
		String uri;
		if (split.hasNext()) {
			uri = "/"+split.next()+Strings.nullToEmpty(request.getQueryString());
		} else {
			uri = "";
		}
		if (!fedi && host.cache()) {
			sendRedirect(response, 307, http+"://"+effectiveHost+"/"+tgtHost+uri);
			return;
		}
		String tgtHttp = (host.insecure()?"http":"https");
		String tgtUri = tgtHttp+"://"+tgtHost+uri;
		if (!host.cache() || JortLink.ignoredHosts.contains(tgtHost)) {
			sendRedirect(response, 301, tgtUri);
			return;
		}
		var idn = InternetDomainName.from(tgtHost);
		if (!idn.hasRegistrySuffix() || idn.isRegistrySuffix()) {
			response.sendError(404);
			return;
		}
		InetAddress[] addrs;
		try {
			addrs = InetAddress.getAllByName(tgtHost);
		} catch (UnknownHostException e) {
			log.warn("Address lookup failed: {}", e.getMessage());
			response.sendError(502, "Address lookup failed");
			return;
		}
		for (var addr : addrs) {
			if (addr.isAnyLocalAddress() || addr.isLinkLocalAddress() || addr.isLoopbackAddress()
					|| addr.isMulticastAddress() || addr.isSiteLocalAddress()) {
				response.sendError(403, "Cowardly refusing to connect to a local address");
				return;
			}
		}
		String hash = Hashing.sha256().hashString(tgtUri, Charsets.UTF_8).toString();
		response.setHeader("Link", "<"+LINK_ESCAPER.escape(tgtUri)+">; rel=\"canonical\"");
		var cachedRes = pasts.getIfPresent(hash);
		if (cachedRes != null) {
			handleResult(cachedRes, request, response);
			return;
		}
		AsyncContext ctx = baseRequest.startAsync(request, response);
		String hashDir = hash.substring(0, 2);
		synchronized (futures) {
			var future = futures.get(hash);
			String errorSuffix = " ("+tgtUri+")";
			if (future == null) {
				future = CompletableFuture.supplyAsync(() -> {
					try {
						var file = JortLink.cacheDir.resolve(hashDir).resolve(hash);
						if (Files.exists(file) && !JortLink.isExpired(file)) {
							var bs = MoreFiles.asByteSource(file);
							try (var in = new CountingInputStream(bs.openStream())) {
								var dis = new DataInputStream(in);
								String type = dis.readUTF();
								int status = dis.readUnsignedShort();
								long ofs = in.getCount();
								return new RequestResult(bs.slice(ofs, bs.size()-ofs), type, true, status);
							}
						}
						var tmpFile = file.resolveSibling(file.getFileName()+".tmp");
						try {
							var resp = JortLink.client.send(HttpRequest.newBuilder(new URI(tgtUri))
									.header("User-Agent", "Mozilla/5.0 (jort.link shield; +https://jort.link)")
									.GET().build(), BodyHandlers.ofInputStream());
							if (resp.headers().firstValueAsLong("Content-Length").orElse(0) > 8*M) {
								resp.body().close();
								return new RequestResult(509, "Response body is too large");
							}
							String type = resp.headers().firstValue("Content-Type").orElse("application/octet-stream");
							var m = CONTENT_TYPE_PATTERN.matcher(type);
							String baseType;
							if (m.find()) {
								baseType = m.group(1);
							} else {
								baseType = "application/octet-stream";
							}
							int status = resp.statusCode();
							long offset = 0;
							long length = 0;
							MoreFiles.createParentDirectories(tmpFile);
							try (var in = ByteStreams.limit(resp.body(), 8*M);
									var out = new CountingOutputStream(Files.newOutputStream(tmpFile))) {
								interface IORunnable { void run() throws IOException; }
								IORunnable writer;
								if ("text/html".equals(baseType)) {
									if (status == 200) status = 203;
									String charset = m.group(2);
									if (charset == null) {
										charset = "utf-8";
										type = "text/html; charset=utf-8";
									}
									var doc = Jsoup.parse(new UncloseableInputStream(in), charset, tgtUri);
									// remove large tags that have no meaning here
									doc.getElementsByTag("svg").remove();
									doc.getElementsByTag("style").remove();
									doc.getElementsByAttributeValue("rel", "stylesheet").remove();
									// remove inline images and the like
									doc.getElementsByAttributeValueStarting("href", "data:").remove();
									doc.getElementsByAttributeValueStarting("src", "data:").remove();
									// rewrite meta content links to use jort.link
									doc.getElementsByTag("meta").forEach((ele) -> {
										if (ele.hasAttr("name")) {
											switch (ele.attr("name")) {
												// don't modify the OpenGraph canonical URL
												case "og:url":
													return;
											}
										}
										if (ele.hasAttr("content")
												&& (ele.attr("content").startsWith("http://") || ele.attr("content").startsWith("https://"))) {
											try {
												var absUrl = ele.attr("abs:content");
												var contentUri = new URI(absUrl);
												var outHost = Host.FRONT;
												switch (contentUri.getScheme()) {
													case "http":
														outHost = Host.INSECURE;
														// fall-thru
													case "https":
														var path = Strings.nullToEmpty(contentUri.getRawPath());
														var query = contentUri.getRawQuery();
														if (query == null) query = "";
														else query = "?"+query;
														ele.attr("content", http+"://"+outHost+"/"+contentUri.getAuthority()+path+query);
														break;
												}
											} catch (URISyntaxException e) {}
										}
									});
									Charset ch;
									try {
										ch = Charset.forName(charset);
									} catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
										ch = StandardCharsets.UTF_8;
									}
									final var fch = ch;
									writer = () -> {
										var w = new OutputStreamWriter(out, fch);
										doc.html(w);
										w.flush();
									};
								} else {
									writer = () -> {
										in.transferTo(out);
									};
								}
								var dos = new DataOutputStream(out);
								dos.writeUTF(type);
								dos.writeShort(status);
								offset = out.getCount();
								writer.run();
								length = out.getCount()-offset;
							} catch (IOException e) {
								log.warn("Request failed"+errorSuffix, e);
								return new RequestResult(502, "Request failed");
							}
							// the destination may exist if we are re-retrieving after expiring a cache entry
							Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING);
							return new RequestResult(MoreFiles.asByteSource(file).slice(offset, length), type, false, status);
						} catch (URISyntaxException | InterruptedException e) {
							log.warn("Request failed"+errorSuffix, e);
							return new RequestResult(502, "Request failed");
						} finally {
							Files.deleteIfExists(tmpFile);
						}
					} catch (IOException e) {
						log.error("Internal error"+errorSuffix, e);
						return new RequestResult(500, "Internal server error");
					}
				}).whenComplete((res, t) -> {
					if (res.data != null) {
						synchronized (pasts) {
							pasts.put(hash, res.withCached());
						}
					}
					synchronized (futures) {
						futures.remove(hash);
					}
				});
				futures.put(hash, future);
			}
			future.whenComplete((res, t) -> {
				if (res == null) {
					log.error("Future did not complete successfully"+errorSuffix, t);
					res = new RequestResult(500, "Internal server error");
				}
				try {
					handleResult(res, request, response);
				} catch (IOException e) {
					// EofException means the client closed the connection, we don't care
					if (!(e instanceof EofException)) {
						log.error("Internal error", e);
						try {
							response.sendError(500);
						} catch (IOException e1) {}
					}
				}
				ctx.complete();
			});
		}
	}

	private void handleResult(RequestResult res, HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (res.data == null) {
			if (HttpStatus.isRedirection(res.status)) {
				sendRedirect(response, res.status, res.message);
			} else {
				response.sendError(res.status, res.message);
			}
		} else {
			for (var l : res.data.sizeIfKnown().asSet()) {
				response.setContentLengthLong(l);
			}
			response.setStatus(res.status);
			response.setHeader("Cache-Control", "public, max-age=86400");
			response.setHeader("Content-Type", res.contentType);
			response.setHeader("Upstream-Cache", res.cached ? "HIT" : "MISS");
			if ("GET".equals(request.getMethod())) {
				if (res.data instanceof GZIPByteSource gz && canAccept(request, "Accept-Encoding", "gzip")) {
					// I ended up dummying this out because no fedi software actually supports this, but it may become useful later
					response.setHeader("Content-Encoding", "gzip");
					try (var in = gz.openRawStream()) {
						in.transferTo(response.getOutputStream());
					}
				} else {
					try (var in = res.data.openStream()) {
						in.transferTo(response.getOutputStream());
					}
				}
			}
			try {
				response.getOutputStream().close();
			} catch (EofException e) {}
		}
	}

	private void sendRedirect(HttpServletResponse response, int status, String target) throws IOException {
		response.setStatus(status);
		response.setHeader("Location", target);
		response.getOutputStream().close();
	}

	private boolean canAccept(HttpServletRequest req, String header, String value) {
		return req.getHeader(header) != null && Iterables.any(ACCEPT_SPLITTER.split(req.getHeader(header)), t -> {
			int semi = t.indexOf(';');
			if (semi != -1) t = t.substring(0, semi);
			return t.equals(value);
		});
	}
	
}