import io.activej.http.AsyncServlet;
import io.activej.http.HttpResponse;
import io.activej.http.WebSocketDecorator;
import io.activej.inject.annotation.Provides;
import io.activej.launchers.http.HttpServerLauncher;

public final class WebSocketEchoServerExample extends HttpServerLauncher {

	@Provides
	AsyncServlet servlet() {
		return WebSocketDecorator.webSocket(request -> HttpResponse.ok200().withBodyStream(request.getBodyStream()));
	}

	public static void main(String[] args) throws Exception {
		WebSocketEchoServerExample launcher = new WebSocketEchoServerExample();
		launcher.launch(args);
	}
}
