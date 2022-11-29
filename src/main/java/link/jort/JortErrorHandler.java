package link.jort;

import java.io.IOException;
import java.io.Writer;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ErrorHandler;
import com.google.common.html.HtmlEscapers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class JortErrorHandler extends ErrorHandler {

	@Override
	protected void generateAcceptableResponse(Request baseRequest, HttpServletRequest request, HttpServletResponse response, int code, String message) throws IOException {
		generateAcceptableResponse(baseRequest, request, response, code, message, MimeTypes.Type.TEXT_HTML.asString());
	}
    
	@Override
	protected void writeErrorPage(HttpServletRequest request, Writer writer, int code, String message, boolean showStacks)
			throws IOException {
		writer.write("<!DOCTYPE html>\n");
		super.writeErrorPage(request, writer, code, message, showStacks);
	}
	
	@Override
	protected void writeErrorPageHead(HttpServletRequest request, Writer writer, int code, String message) throws IOException {
		super.writeErrorPageHead(request, writer, code, HttpStatus.getMessage(code));
		writer.write("""
				<meta name="viewport" content="width=device-width, initial-scale=1">
				<style>
				body {
					background: #DDD;
					color: #114;
					font-family: system-ui, sans-serif;
					max-width: 560px;
					line-height: 1.4;
					tab-size: 4;
				}
				h1 {
					font-family: serif;
				}
				a:link, a:visited {
					color: #02F !important;
				}
				</style>
				""");
	}
	
	@Override
	protected void writeErrorPageBody(HttpServletRequest request, Writer writer, int code, String message, boolean showStacks) throws IOException {
		writeErrorPageMessage(request,writer,code,message,request.getRequestURI());
	}
	
	
	@Override
	protected void writeErrorPageMessage(HttpServletRequest request, Writer writer, int code, String errorMessage, String uri) throws IOException {
		String msg = HttpStatus.getMessage(code);
		writer.write("\n<h1>jort.link &mdash; ");
		writer.write(Integer.toString(code));
		writer.write(" ");
		writer.write(msg);
		writer.write("</h2>\n<p>");
		writer.write(switch (code) {
			case 400 -> "Your request was malformed and we're refusing to serve it.";
			case 403 -> "Something about your request doesn't add up, so we're refusing to serve it.";
			case 404 -> "Whatever you're looking for, it's not here.";
			case 405 -> "jort.link only accepts HEAD and GET.";
			case 421 -> "We don't recognize the host "+HtmlEscapers.htmlEscaper().escape(request.getHeader("Host"));
			case 500 -> "Something exploded! Please let Una know, such as via <a href=\"mailto:me@unascribed.com\">email</a> or <a href=\"https://mastodon.sleeping.town/@unascribed\">the Fediverse</a>.";
			case 502 -> "We couldn't contact the remote server.";
			case 509 -> "The remote server returned a response larger than we're willing to process.";
			default -> HttpStatus.getMessage(code);
		});
		writer.write("</p>\n");
		if (!msg.equals(errorMessage)) {
			writer.write("<p>Error message: ");
			writer.write(HtmlEscapers.htmlEscaper().escape(errorMessage));
			writer.write("</p>\n");
		}
		writer.write("<hr>\n<a href=\"");
		writer.write(JortLink.http);
		writer.write("://");
		writer.write(Host.FRONT.toString());
		writer.write("\">");
		writer.write(Host.FRONT.toString());
		if ("jort.link".equals(Host.FRONT.toString())) {
			writer.write("</a> - Copyright &copy; 2022-2023 <a href=\"https://unascribed.com\">Una Thompson (unascribed)</a>");
		} else {
			writer.write("</a> - powered by <a href=\"https://github.com/jortage/jort.link\">jort.link</a>");
		}
	}
	
}
