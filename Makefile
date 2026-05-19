ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))

WEBSERVER_JAR := webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar
JAVASSIST_JAR := javassist/target/JavassistWrapper-1.0-jar-with-dependencies.jar
DUMP_DIR := $(ROOT_DIR)/dump

SERVER_MAIN := pt.ulisboa.tecnico.cnv.webserver.WebServer
SERVER_AGENT := ICount:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna:output

# Default arguments for the handlers
DNA_ARGS ?= human:$(ROOT_DIR)/dna/src/main/resources/human_HBB.fasta chimpanzee:$(ROOT_DIR)/dna/src/main/resources/chimpanzee_HBB.fasta 10 false
FRACTALS_ARGS ?= 800 600 100 $(DUMP_DIR)/fractals/julia.png
GRAYSCOTT_ARGS ?= 256 10000 0.030 0.062 false stripe $(DUMP_DIR)/grayscott/grayscott.png

.PHONY: all compile clean run-server server dna fractals grayscott

all: compile

compile:
	mvn clean package

clean: clean-dump
	mvn clean

run-server: compile
	mkdir -p $(DUMP_DIR)
	java \
		-cp $(WEBSERVER_JAR) \
		-Xbootclasspath/a:$(JAVASSIST_JAR) \
		-javaagent:$(WEBSERVER_JAR)=$(SERVER_AGENT) \
		$(SERVER_MAIN) > $(DUMP_DIR)/log.txt 2>&1

server: run-server

dna:
	mkdir -p $(DUMP_DIR)/dna
	cd $(DUMP_DIR)/dna && java -cp $(ROOT_DIR)/dna/target/dna-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
		pt.ulisboa.tecnico.cnv.dna.DnaHandler $(DNA_ARGS)

fractals:
	mkdir -p $(DUMP_DIR)/fractals
	java -cp $(ROOT_DIR)/fractals/target/fractals-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
		pt.ulisboa.tecnico.cnv.fractals.FractalsHandler $(FRACTALS_ARGS)

grayscott:
	mkdir -p $(DUMP_DIR)/grayscott
	java -cp $(ROOT_DIR)/grayscott/target/grayscott-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
		pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler $(GRAYSCOTT_ARGS)

clean-dump:
	rm -rf $(DUMP_DIR) 