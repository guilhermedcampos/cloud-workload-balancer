#!/bin/bash

java \
-cp webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
-Xbootclasspath/a:javassist/target/JavassistWrapper-1.0-jar-with-dependencies.jar \
-javaagent:webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar=ICount:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna:output \
pt.ulisboa.tecnico.cnv.webserver.WebServer