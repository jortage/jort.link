# jort.link
jort.link is a URL redirector to solve an issue in the Fediverse where a
[poor design decision in Mastodon](https://github.com/mastodon/mastodon/issues/4486) (carried over
to Pleroma and Akkoma) means simply linking something on the Fediverse will cause that URL to receive
hundreds of requests over a short period. This is enough to take small servers or expensive endpoints
offline. This has been documented multiple times.

This repository is a rewrite of the original nginx/dnsmasq/bunny hack in Java, for more robustness
and more ability to add features.

See the [jort.link site](https://jort.link) for more information.

## Building
This project uses Gradle as its build system, and to create a runnable JAR, you need to invoke
Shadow, like so:

`./gradlew build shadow`

Or, on Windows:

`gradlew.bat build shadow`

If there's interest, I can start publishing pre-built JARs.

## Running
Edit config.example.json to match your setup. Then, run the JAR with Java 17. You'll likely want to
run it behind a reverse proxy to handle TLS, and maybe a caching CDN like bunny.net to reduce origin
request volume.
