
JAVA_PACKAGE = com.tailf.packages.ned.alusr
JDIR = $(shell echo $(JAVA_PACKAGE) | sed 's/\./\//g')

NS  = namespaces

JFLAGS = --java-disable-prefix \
         --exclude-enums \
         --fail-on-warnings \
         --java-package $(JAVA_PACKAGE).$(NS) \
         --emit-java java/src/$(JDIR)/namespaces

all:	fxs javac
	cd ../netsim && $(MAKE) all

# Include standard NCS examples build definitions and rules
include $(NCS_DIR)/src/ncs/build/include.ncs.mk

YANG = $(wildcard yang/*.yang)
SUBMODULES = $(shell grep -l belongs-to yang/*.yang)
YANG_MODULES = $(filter-out $(SUBMODULES),$(YANG))

FXS = $(YANG_MODULES:yang/%.yang=ncsc-out/modules/fxs/%.fxs)

$(FXS): $(YANG)

fxs:	ncsc-out/modules/fxs/tailf-ned-alu-sr.fxs \
	ncsc-out/modules/fxs/tailf-ned-alu-sr-id.fxs \
	ncsc-out/modules/fxs/tailf-ned-alu-sr-stats.fxs \
	../load-dir/tailf-ned-alu-sr-meta.fxs
# Generate yang from output from alu's tree command. The real alu.yang is
# based on this output but has manual modifications.  Use a mergetool like
# vimdiff or meld to sync the generated file with alu.yang.
tree: data/alu.yang
data/alu.yang: data/alu.tree
	python tools/tree/compiler.py $< > $@

ncsc-out/modules/fxs/tailf-ned-alu-sr.fxs:	yang/tailf-ned-alu-sr.yang
	$(NCSC) --ncs-compile-module $< \
                --ncs-device-dir ncsc-out \
                --ncs-skip-statistics \
                --ncs-device-type cli-ned && \
	  cp $@ ../load-dir
	$(NCSC) $(JFLAGS)/tailfNedAluSr.java $@

ncsc-out/modules/fxs/tailf-ned-alu-sr-id.fxs:	yang/tailf-ned-alu-sr-id.yang
	$(NCSC) --ncs-compile-module $< \
                --ncs-device-dir ncsc-out \
                --ncs-skip-statistics \
                --ncs-device-type cli-ned && \
	  cp $@ ../load-dir
	$(NCSC) $(JFLAGS)/tailfNedAluSrId.java $@

ncsc-out/modules/fxs/tailf-ned-alu-sr-stats.fxs: \
	yang/tailf-ned-alu-sr-stats.yang
	$(NCSC) --ncs-compile-module $< \
			--ncs-skip-config \
			--ncs-device-dir ncsc-out \
			--ncs-skip-template \
			--ncs-device-type cli-ned && \
	cp $@ ../load-dir

../load-dir/tailf-ned-alu-sr-meta.fxs:	yang/tailf-ned-alu-sr-meta.yang
	$(NCSC) --yangpath yang -c $< -o $@

javac:
	cd java && ant -q all

clean:
	rm -rf ncsc-out/* ../load-dir/* ncsc-out/*
	rm -f ../jar/*.jar
	rm -f java/src/$(JDIR)/$(NS)/*.java
	cd java && ant clean
	cd ../netsim && $(MAKE) clean
