package link.jort;

import java.io.File;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.asyncsimplelog.AsyncSimpleLog;

import com.google.common.base.Ascii;
import com.google.common.base.Stopwatch;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonArray;
import blue.endless.jankson.JsonElement;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.JsonPrimitive;

public class JortLink {

	private static final Logger log = LoggerFactory.getLogger(JortLink.class);
	
	private static final File configFile = new File("config.jkson");
	public static JsonObject config;

	public static String http = "https";
	public static final Set<String> ignoredHosts = new HashSet<>();
	public static final BiMap<String, Host> hosts = HashBiMap.create();
	public static final List<Pattern> uaPatterns = new ArrayList<>();
	public static Path cacheDir, filesDir;
	
	
	public static final HttpClient client = HttpClient.newBuilder()
			.followRedirects(Redirect.ALWAYS)
			.build();
	
	public static void main(String[] args) throws Exception {
		try {
			System.setProperty("networkaddress.cache.ttl", "30");
			System.setProperty("networkaddress.cache.negative.ttl", "10");
			AsyncSimpleLog.setAnsi(true);
			AsyncSimpleLog.startLogging();
			
			Stopwatch initSw = Stopwatch.createStarted();
			config = Jankson.builder()
						.allowBareRootObject()
					.build().load(configFile);
			
			for (Host h : Host.values()) {
				hosts.put(config.recursiveGet(String.class, "hosts."+Ascii.toLowerCase(h.name())), h);
			}
			if (!config.getBoolean("https", true)) {
				http = "http";
			}
	
			String host = config.get(String.class, "bind");
			int port = config.getInt("port", 17312);

			cacheDir = FileSystems.getDefault().getPath(config.get(String.class, "cache"));
			filesDir = FileSystems.getDefault().getPath(config.get(String.class, "files"));
			
			config.get(JsonArray.class, "uaPatterns").stream()
				.mapMulti(JortLink::strings)
				.map(Pattern::compile)
				.forEach(uaPatterns::add);
			
			config.get(JsonArray.class, "ignoredHosts").stream()
				.mapMulti(JortLink::strings)
				.forEach(ignoredHosts::add);
			
			Server server = new Server();
			ServerConnector conn = new ServerConnector(server);
			conn.setHost(host);
			conn.setPort(port);
			server.addConnector(conn);
			server.setHandler(new OuterHandler(new JortLinkHandler()));
			server.start();
			log.info("Ready on http://{}:{}", host, port);
			
			log.info("This JortLink has Super Denim Powers. (Done in {})", initSw);
		} catch (Throwable t) {
			log.error("Failed to start", t);
		}
	}
	
	private static void strings(JsonElement ele, Consumer<String> out) {
		if (ele instanceof JsonPrimitive jp) out.accept(jp.asString());
	}
	
}
