
JAVA_PACKAGE = com.tailf.packages.ned.ios
JDIR = $(shell echo $(JAVA_PACKAGE) | sed 's/\./\//g')

NS  = namespaces

JFLAGS = --java-disable-prefix \
         --exclude-enums \
         --fail-on-warnings \
         --java-package $(JAVA_PACKAGE).$(NS) \
         --emit-java

all:	fxs javac
	cd ../netsim && $(MAKE) all

# Include standard NCS examples build definitions and rules
include $(NCS_DIR)/src/ncs/build/include.ncs.mk

YANG = yang/tailf-ned-cisco-ios.yang \
       yang/tailf-ned-cisco-ios-id.yang \
       yang/tailf-ned-cisco-ios-stats.yang

SUBMODULES = $(shell grep -l belongs-to yang/*.yang)
YANG_MODULES = $(filter-out $(SUBMODULES),$(YANG))

FXS = $(YANG_MODULES:yang/%.yang=ncsc-out/modules/fxs/%.fxs)

JAVA_NAMESPACES = \
	$(shell echo $(YANG:yang/%.yang=java/src/$(JDIR)/namespaces/%.java) | \
		perl -pne 's/([a-zA-Z])-([a-zA-Z])/\1\U\2/g' | \
		perl -pne 's/tailfNed//g')

fxs:	$(FXS)

namespaces: $(JAVA_NAMESPACES)

java/src/$(JDIR)/namespaces/CiscoIosStats.java:\
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-stats.fxs
	$(NCSC) $(JFLAGS) $@ $<

java/src/$(JDIR)/namespaces/CiscoIos.java:\
	ncsc-out/modules/fxs/tailf-ned-cisco-ios.fxs
	$(NCSC) $(JFLAGS) $@ $<

java/src/$(JDIR)/namespaces/CiscoIosId.java:\
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-id.fxs
	$(NCSC) $(JFLAGS) $@ $<

ncsc-out/modules/fxs/tailf-ned-cisco-ios-stats.fxs:\
	yang/tailf-ned-cisco-ios-stats.yang
	$(NCSC) --ncs-compile-module $< \
                --ncs-device-dir ncsc-out \
	        --ncs-skip-config \
	        --ncs-skip-template \
                --ncs-device-type cli-ned && \
	  cp $@ ../load-dir

ncsc-out/modules/fxs/tailf-ned-cisco-ios.fxs:\
	yang/tailf-ned-cisco-ios.yang
	$(NCSC) --ncs-compile-module $< \
                --ncs-device-dir ncsc-out \
	  	--ncs-skip-statistics \
                --ncs-device-type cli-ned && \
	  cp $@ ../load-dir

ncsc-out/modules/fxs/tailf-ned-cisco-ios-id.fxs:\
	yang/tailf-ned-cisco-ios-id.yang
	$(NCSC) --ncs-compile-module $< \
                --ncs-device-dir ncsc-out \
	  	--ncs-skip-statistics \
                --ncs-device-type cli-ned && \
	  cp $@ ../load-dir

javac: namespaces
	cd java && ant -q all

clean:
	rm -rf ncsc-out/* ../load-dir/* ncsc-out/*
	rm -f ../jar/*.jar
	rm -f java/src/$(JDIR)/$(NS)/*.java
	cd java && ant clean
	cd ../netsim && $(MAKE) clean
