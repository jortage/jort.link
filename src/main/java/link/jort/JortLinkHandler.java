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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.escape.Escaper;
import com.google.common.escape.Escapers;
import com.google.common.hash.Hashing;
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

public final class JortLinkHandler extends HandlerWrapper {
	private static final Logger log = LoggerFactory.getLogger(JortLinkHandler.class);

	private static final long M = 1024*1024;
	
	record RequestResult(Path file, long offset, String contentType, boolean cached, int status, String message) {
		
		public RequestResult(Path file, long offset, String contentType, boolean cached, int status) {
			this(file, offset, contentType, cached, status, null);
		}
		
		public RequestResult(int status, String message) {
			this(null, 0, null, false, status, message);
		}
		
		public RequestResult withCached() {
			return new RequestResult(file, offset, contentType, true, status, message);
		}
	}

	private final Map<String, CompletableFuture<RequestResult>> futures = new HashMap<>();
	private final Cache<String, RequestResult> pasts = CacheBuilder.newBuilder()
			.expireAfterAccess(2, TimeUnit.HOURS)
			.maximumSize(1024)
			.softValues()
			.build();
	
	private static final Splitter SLASH_SPLITTER2 = Splitter.on('/').limit(2).omitEmptyStrings();
	private static final Splitter SLASH_SPLITTER3 = Splitter.on('/').limit(3).omitEmptyStrings();

	private static final Pattern CONTENT_TYPE_PATTERN = Pattern.compile("^([^;]*)(?:$|;\\s*(?:charset=(.*))?)");
	
	private static final Escaper LINK_ESCAPER = Escapers.builder()
			.addEscape('<', "%3C")
			.addEscape('>', "%3E")
			.build();
	
	public JortLinkHandler() {
		var resource = new ResourceHandler();
		resource.setResourceBase(JortLink.filesDir.toString());
		resource.setDirectoriesListed(false);
		resource.setWelcomeFiles(new String[] {"index.html"});
		resource.setCacheControl("public, max-age=86400");
		resource.setRedirectWelcome(false);
		resource.setPrecompressedFormats(new CompressedContentFormat[] {
				CompressedContentFormat.GZIP,
				CompressedContentFormat.BR
		});
		setHandler(resource);
	}

	@Override
	public void handle(String target, Request request, HttpServletRequest bareServletRequest, HttpServletResponse response) throws IOException, ServletException {
		Host host = Host.of(request.getHeader("Host"));
		if (host == null) {
			response.sendError(421);
			return;
		}
		response.setHeader("Referrer-Policy", "no-referrer");
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
		if (target.startsWith("/.well-known/")) {
			request.setHandled(false);
			super.handle(target, request, bareServletRequest, response);
			return;
		}
		String ua = request.getHeader("User-Agent");
		boolean fedi = false;
		if (ua != null) {
			for (var p : JortLink.uaPatterns) {
				if (p.matcher(ua).find()) {
					fedi = true;
					break;
				}
			}
		}
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
				split = SLASH_SPLITTER2.split(target).iterator();
			}
		} else {
			split = SLASH_SPLITTER2.split(target).iterator();
		}
		if (fedi && effectiveHost.exclude()) {
			response.setHeader("Cache-Control", "public, max-age=86400");
			response.setStatus(204);
			response.getOutputStream().close();
			return;
		}
		if (!split.hasNext()) {
			serveFile(host, target, request, bareServletRequest, response);
			return;
		}
		String tgtHost = split.next();
		if (!InternetDomainName.isValid(tgtHost)) {
			serveFile(host, target, request, bareServletRequest, response);
			return;
		}
		var idn = InternetDomainName.from(tgtHost);
		if (!idn.hasRegistrySuffix() || idn.isRegistrySuffix()) {
			serveFile(host, target, request, bareServletRequest, response);
			return;
		}
		String uri;
		if (split.hasNext()) {
			uri = "/"+split.next()+urifyQuery(request.getQueryString());
		} else {
			uri = "";
		}
		if (!fedi && host.cache()) {
			sendRedirect(response, 307, http+"://"+effectiveHost+"/"+tgtHost+uri);
			return;
		}
		String tgtHttp = (host.insecure()?"http":"https");
		String tgtUri = tgtHttp+"://"+tgtHost+uri;
		if ((JortLink.useCacheDomain ? !host.cache() : !fedi) || JortLink.ignoredHosts.contains(tgtHost)) {
			sendRedirect(response, 301, tgtUri);
			return;
		}
		if (JortLink.useCacheDomain && fedi && !host.cache()) {
			sendRedirect(response, 307, http+"://"+Host.CACHE+"/"+host+target);
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
		if (cachedRes != null && (cachedRes.file == null || Files.exists(cachedRes.file))) {
			handleResult(cachedRes, request, response);
			return;
		}
		AsyncContext ctx = request.startAsync(request, response);
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
								return new RequestResult(file, ofs, type, true, status);
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
									doc.getElementsByTag("script").remove();
									doc.getElementsByAttributeValue("rel", "stylesheet").remove();
									doc.getElementsByAttribute("style").forEach((e) -> {
										e.removeAttr("style");
									});
									doc.getElementsByAttribute("data-jortlink-remove").remove();
									// remove inline images and the like
									doc.getElementsByAttributeValueStarting("href", "data:").remove();
									doc.getElementsByAttributeValueStarting("src", "data:").remove();
									// rewrite potentially interesting links to use jort.link
									doc.getElementsByTag("meta").forEach(processLink("content"));
									doc.getElementsByTag("link").forEach(processLink("href"));
									doc.getElementsByTag("img").forEach(processLink("src"));
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
							} catch (IOException e) {
								log.warn("Request failed"+errorSuffix, e);
								return new RequestResult(502, "Request failed");
							}
							// the destination may exist if we are re-retrieving after expiring a cache entry
							Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING);
							return new RequestResult(file, offset, type, false, status);
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
					synchronized (pasts) {
						pasts.put(hash, res.withCached());
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

	private String urifyQuery(String str) {
		if (str == null) return "";
		return "?"+str;
	}

	private Consumer<? super Element> processLink(String value) {
		return (ele) -> {
			if (isCanonicalMeta(ele.attr("name")) || isCanonicalMeta(ele.attr("property"))
					 || isCanonicalMeta(ele.attr("rel"))) {
				return;
			}
			if (ele.hasAttr(value) && (ele.attr(value).startsWith("http://") || ele.attr(value).startsWith("https://"))) {
				try {
					var absUrl = ele.attr("abs:"+value);
					var contentUri = new URI(absUrl);
					var outHost = Host.FRONT;
					switch (contentUri.getScheme()) {
						case "http":
							outHost = Host.INSECURE;
							// fall-thru
						case "https":
							var path = Strings.nullToEmpty(contentUri.getRawPath());
							var query = urifyQuery(contentUri.getRawQuery());
							ele.attr(value, http+"://"+Host.CACHE+"/"+outHost+"/"+contentUri.getAuthority()+path+query);
							break;
					}
				} catch (URISyntaxException e) {}
			}
		};
	}

	private boolean isCanonicalMeta(String attr) {
		if (attr == null) return false;
		return switch (attr) {
			case "og:url" -> true;
			case "canonical" -> true;
			case "alternate" -> true;
			case "shortlink" -> true;
			default -> false;
		};
	}

	private void serveFile(Host host, String target, Request request, HttpServletRequest bareServletRequest, HttpServletResponse response) throws IOException, ServletException {
		if (host != Host.FRONT) {
			sendRedirect(response, 301, http+"://"+Host.FRONT+target+urifyQuery(request.getQueryString()));
			return;
		}
		request.setHandled(false);
		super.handle(target, request, bareServletRequest, response);
	}

	private void handleResult(RequestResult res, Request request, HttpServletResponse response) throws IOException {
		response.setHeader("Upstream-Cache", res.cached ? "HIT" : "MISS");
		if (res.file == null) {
			if (HttpStatus.isRedirection(res.status)) {
				sendRedirect(response, res.status, res.message);
			} else {
				response.setHeader("Cache-Control", "public, max-age=7200");
				response.sendError(res.status, res.message);
			}
		} else {
			response.setContentLengthLong(Files.size(res.file));
			response.setStatus(res.status);
			response.setHeader("Cache-Control", "public, max-age=86400");
			response.setHeader("Content-Type", res.contentType);
			if ("GET".equals(request.getMethod())) {
				try (var in = Files.newInputStream(res.file)) {
					ByteStreams.skipFully(in, res.offset);
					in.transferTo(response.getOutputStream());
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
	
}