package link.jort;

import java.io.IOException;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.Jetty;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class OuterHandler extends HandlerWrapper {

	private static final String JAVA_VERSION = System.getProperty("java.version");
	
	public OuterHandler(Handler delegate) {
		setHandler(delegate);
	}
	
	@Override
	public void handle(String target, Request baseRequest, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException {
		res.setHeader("Powered-By", "jort.link/"+JortLink.VERSION+" Jetty/"+Jetty.VERSION+" Java/"+JAVA_VERSION);
		res.setHeader("Clacks-Overhead", "GNU Natalie Nguyen, Amelia Rose");
		res.setHeader("Jeans-Teleshorted", Integer.toString((int)(Math.random()*200000)+70));
		super.handle(target, baseRequest, req, res);
	}
	
}
