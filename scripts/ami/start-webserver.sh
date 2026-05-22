#!/bin/bash
cd /home/ec2-user
java \
    -cp webserver.jar \
    -javaagent:/home/ec2-user/javassist.jar=ICount:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna:output \
    pt.ulisboa.tecnico.cnv.webserver.WebServer \
    >> /home/ec2-user/webserver.log 2>&1
