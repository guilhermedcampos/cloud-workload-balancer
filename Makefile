ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))

WEBSERVER_JAR := webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar
JAVASSIST_JAR := javassist/target/JavassistWrapper-1.0-jar-with-dependencies.jar
DUMP_DIR := $(ROOT_DIR)/dump
TESTS_CHECKPOINT_OUT := $(DUMP_DIR)/tests-checkpoint
TESTS_CHECKPOINT_RESULTS := $(DUMP_DIR)/tests-checkpoint-results

SERVER_MAIN := pt.ulisboa.tecnico.cnv.webserver.WebServer
SERVER_AGENT := ICount:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna:output


# DNA default arguments
DNA_DIR := $(ROOT_DIR)/dna/src/main/resources/

S1 ?= SEQ1
S2 ?= SEQ2
LENGTH ?= 10
STOP ?= false

DNA_OUT := $(DUMP_DIR)/dna/dna-match-result.html


# Fractals default arguments
W ?= 800
H ?= 600
I ?= 100

FRACTALS_OUT := $(DUMP_DIR)/fractals/julia.png


# Grayscott default arguments
SIZE ?= 256
MI ?= 10000
F ?= 0.030
K ?= 0.062
STOP ?= false # true or false
SEED ?= stripe # center / ring / stripe

GRAYSCOTT_OUT := $(DUMP_DIR)/grayscott/grayscott.png


.PHONY: all compile clean run-server server dna fractals grayscott plots plots-extended tests-checkpoint

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
	curl "http://localhost:8000/dna?seq1=$(S1)&seq2=$(S2)&minLength=$(LENGTH)&stopOnFirst=$(STOP)" > $(DNA_OUT)

fractals:
	mkdir -p $(DUMP_DIR)/fractals
	curl -s "http://127.0.0.1:8000/fractals?w=$(W)&h=$(H)&iterations=$(I)"\
	| awk -F',' '{print $$2}' | tr -d '" \n\r' | base64 -d > $(FRACTALS_OUT)

grayscott:
	mkdir -p $(DUMP_DIR)/grayscott
	curl -s "http://127.0.0.1:8000/grayscott?size=$(SIZE)&maxIterations=$(MI)&f=$(F)&k=$(K)&stopOnExtinction=$(STOP)&seedMode=$(SEED)"\
	| awk -F',' '{print $$2}' | tr -d '" \n\r' | base64 -d > $(GRAYSCOTT_OUT)

plots:
	mkdir -p $(DUMP_DIR)/plots
	python3 scripts/plot_metrics.py --log metrics.log --outdir $(DUMP_DIR)/plots

plots-extended:
	mkdir -p $(DUMP_DIR)/plots-extended
	python3 scripts/analysis_metrics.py --log metrics.log --outdir $(DUMP_DIR)/plots-extended

tests-checkpoint:
	@echo "Running all scripts in tests-checkpoint/ (logs -> $(TESTS_CHECKPOINT_OUT), results -> $(TESTS_CHECKPOINT_RESULTS))"
	@mkdir -p $(TESTS_CHECKPOINT_OUT) $(TESTS_CHECKPOINT_RESULTS); \
	failures=0; \
	for s in tests-checkpoint/*.sh; do \
		base=$$(basename $$s); \
		log="$(TESTS_CHECKPOINT_OUT)/$$base.log"; \
		echo; echo "=== Running $$s (log: $$log) ==="; \
		if echo "$$base" | grep -q 'dna'; then \
			out="$(TESTS_CHECKPOINT_RESULTS)/$${base%.sh}.html"; \
			echo "   (DNA: stdout -> $$out)"; \
			(cd $(TESTS_CHECKPOINT_RESULTS) && bash $(ROOT_DIR)/"$$s" > "$$out" 2> "$$log") || failures=$$((failures+1)); \
		else \
			echo "   (fractals/grayscott: files to $(TESTS_CHECKPOINT_RESULTS))"; \
			(cd $(TESTS_CHECKPOINT_RESULTS) && bash $(ROOT_DIR)/"$$s" > /dev/null 2> "$$log") || failures=$$((failures+1)); \
		fi; \
	done; \
	if [ $$failures -ne 0 ]; then echo "One or more scripts failed: $$failures (see logs in $(TESTS_CHECKPOINT_OUT))"; exit 1; fi

clean-dump:
	rm -rf $(DUMP_DIR)
	