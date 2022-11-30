# jort.link
jort.link is a URL redirector to solve an issue in the Fediverse where a
[poor design decision in Mastodon](https://github.com/mastodon/mastodon/issues/4486) (carried over
to Pleroma and Akkoma) means simply linking something on the Fediverse will cause that URL to receive
hundreds of requests over a short period. This is enough to take small servers or expensive endpoints
offline. This has been documented multiple times.

This repository is a rewrite of the original nginx/dnsmasq/bunny hack in Java, for more robustness
and more ability to add features. It's also completely self-contained and relatively easy to run.

See the [jort.link site](https://jort.link) for more information.

## Building
This project uses Gradle as its build system, and can be built like any normal Gradle project:

`./gradlew build`

The wrapper will handle obtaining the proper version of Gradle for you — you just need a JDK
installed. Your runnable JAR will be in `build/libs`.

## Running
Copy config.example.jkson to config.jkson, and edit it to match your setup. Then, run the JAR with
Java 17. You'll likely want to run it behind a reverse proxy to handle TLS, and integrate it with
your service manager of choice.

There's no database or anything; a near-term cache of meta information is kept in memory, and
everything else is stored in normal files on disk in the configured cache directory. Every 4 hours,
the cache directory will be scanned for files older than 24 hours, and those will be deleted.

By default, Java will use as much RAM as it can get away with. You will likely want to limit this by
passing a maximum memory parameter — for example, `java -Xmx128M -jar jortlink.jar` will limit it to
128M. You can go lower.
