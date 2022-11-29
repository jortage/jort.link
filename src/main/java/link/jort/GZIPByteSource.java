package link.jort;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import com.google.common.base.Optional;
import com.google.common.io.ByteSource;

public class GZIPByteSource extends ByteSource {

	private final ByteSource delegate;
	
	public GZIPByteSource(ByteSource delegate) {
		this.delegate = delegate;
	}

	public InputStream openRawStream() throws IOException {
		return delegate.openStream();
	}
	
	@Override
	public InputStream openStream() throws IOException {
		return new GZIPInputStream(delegate.openStream());
	}

	@Override
	public boolean isEmpty() throws IOException {
		return delegate.isEmpty();
	}

	@Override
	public Optional<Long> sizeIfKnown() {
		return delegate.sizeIfKnown();
	}
	
}
