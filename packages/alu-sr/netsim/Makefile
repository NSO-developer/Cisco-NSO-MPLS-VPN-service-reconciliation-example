include $(NCS_DIR)/src/ncs/build/include.netsim.mk

CONFD_FLAGS = --addloadpath $(CONFD_DIR)/etc/confd
START_FLAGS ?=

XML = $(wildcard *.xml)
YANG = tailf-ned-alu-sr.yang
FXS = $(YANG:%.yang=%.fxs)
FILES = confd.conf.netsim confd.c.ccl confd.i.ccl show_version.sh populate.sh show_config.sh show_debug.sh

XML += $(NCS_DIR)/netsim/confd/var/confd/cdb/aaa_init.xml
FXS += aaa.fxs ietf-netconf-acm.fxs
FXS_WERR=

NACMDIR = $(NCS_DIR)/netsim/confd/src/confd/yang

.PHONY: all
all: $(FXS) confd.c.ccl confd.i.ccl

aaa.fxs: $(NCS_DIR)/netsim/confd/src/confd/aaa/tailf-aaa.yang
	$(CONFDC) -c --export none -o $@ $<

ietf-netconf-acm.fxs: $(NACMDIR)/ietf-netconf-acm.yang
	$(CONFDC) -c --export none -a $(NACMDIR)/ietf-netconf-acm-ann.yang\
	         -o $@ $<

%.fxs:	../src/yang/%.yang
	$(CONFDC) $(FXS_WERR) $(EXTRA_LINK_FLAGS) -c -o $@  $<

$(YANG):
	sed '/tailf:callpoint/d' ../src/yang/$(YANG) > $(YANG)

%.fxs:	$(YANG) %.yang
	$(CONFDC) $(FXS_WERR) $(EXTRA_LINK_FLAGS) --yangpath . -c -o $@  $<

## Invoked by netsim, who will sed substitute the %var% variables
## in the .netsim files
install:
	@if [ -z "$${DEST_DIR}" ]; then echo "No DEST_DIR  var"; exit 1; fi
	@if [ ! -d "$${DEST_DIR}" ]; then "echo No DEST_DIR"; exit 1; fi
	-mkdir $${DEST_DIR}/cdb
	-mkdir $${DEST_DIR}/logs
	cp -f $(XML) $${DEST_DIR}/cdb
	cp -f $(FXS) $${DEST_DIR}
	cp -f $(FILES) $${DEST_DIR}
	cp -af $(CONFD_DIR)/etc/confd/ssh $${DEST_DIR}

clean:
	@rm -f *.ccl *.fxs
	@rm -rf netsim
	@rm -rf $(YANG)

local-netsim: all
	$(NCS_DIR)/bin/ncs-netsim create-network `pwd`/.. 1 a --dir netsim
