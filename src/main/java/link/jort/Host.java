package link.jort;

import javax.annotation.Nullable;

public enum Host {
	FRONT,
	CACHE,
	INSECURE,
	EXCLUDE,
	INSECURE_EXCLUDE,
	;
	
	public boolean cache() {
		return this == CACHE;
	}
	
	public boolean exclude() {
		return this == EXCLUDE || this == INSECURE_EXCLUDE;
	}
	
	public boolean insecure() {
		return this == INSECURE || this == INSECURE_EXCLUDE;
	}
	
	@Override
	public String toString() {
		return JortLink.hosts.inverse().get(this);
	}
	
	public static @Nullable Host of(String hostStr) {
		return JortLink.hosts.get(hostStr);
	}
}
