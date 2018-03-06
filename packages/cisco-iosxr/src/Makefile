
JAVA_PACKAGE = com.tailf.packages.ned.iosxr
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

YANG = yang/tailf-ned-cisco-ios-xr.yang \
       yang/tailf-ned-cisco-ios-xr-id.yang \
       yang/tailf-ned-cisco-ios-xr-stats.yang \
       yang/tailf-ned-cisco-ios-xr-meta.yang

SRC = $(wildcard yang/*.yang)
FXS = $(SRC:yang/%.yang=ncsc-out/modules/fxs/%.fxs)

JAVA_NAMESPACES = \
	$(shell echo $(YANG:yang/%.yang=java/src/$(JDIR)/namespaces/%.java) | \
		perl -pne 's/([a-zA-Z])-([a-zA-Z])/\1\U\2/g' | \
		perl -pne 's/tailfNed//g')

$(FXS): $(YANG)

fxs:	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr.fxs \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-stats.fxs \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-id.fxs \
	../load-dir/tailf-ned-cisco-ios-xr-meta.fxs

namespaces: $(JAVA_NAMESPACES)

java/src/$(JDIR)/namespaces/CiscoIosXr.java: \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr.fxs
	$(NCSC) $(JFLAGS) $@ $<

java/src/$(JDIR)/namespaces/CiscoIosXrId.java: \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-id.fxs
	$(NCSC) $(JFLAGS) $@ $<

java/src/$(JDIR)/namespaces/CiscoIosXrStats.java: \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-stats.fxs
	$(NCSC) $(JFLAGS) $@ $<

java/src/$(JDIR)/namespaces/CiscoIosXrMeta.java: \
	ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-stats.fxs
	$(NCSC) $(JFLAGS) $@ $<

ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr.fxs: yang/tailf-ned-cisco-ios-xr.yang
	$(NCSC) --ncs-compile-module $< \
		--ncs-device-dir ncsc-out \
		--ncs-skip-statistics \
		--ncs-device-type cli-ned && \
	cp $@ ../load-dir

ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-stats.fxs: yang/tailf-ned-cisco-ios-xr-stats.yang
	$(NCSC) --ncs-compile-module $< \
	        --ncs-skip-config \
		--ncs-skip-template \
		--ncs-device-dir ncsc-out \
		--ncs-device-type cli-ned && \
	cp $@ ../load-dir

ncsc-out/modules/fxs/tailf-ned-cisco-ios-xr-id.fxs: yang/tailf-ned-cisco-ios-xr-id.yang
	$(NCSC) --ncs-compile-module $< \
		--ncs-device-dir ncsc-out \
		--ncs-skip-statistics \
		--ncs-device-type cli-ned && \
	cp $@ ../load-dir

../load-dir/tailf-ned-cisco-ios-xr-meta.fxs:	yang/tailf-ned-cisco-ios-xr-meta.yang
	$(NCSC) --yangpath yang -c $< -o $@

javac: namespaces
	cd java && ant -q all

clean:
	rm -rf ncsc-out/* ../load-dir/* ncsc-out/*
	rm -f ../jar/*.jar
	rm -f java/src/$(JDIR)/$(NS)/*.java
	cd java && ant clean
	cd ../netsim && $(MAKE) clean
