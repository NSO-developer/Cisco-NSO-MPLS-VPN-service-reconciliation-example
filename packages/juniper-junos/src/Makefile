
SRC = yang/junos.yang yang/junos-rpc.yang
FXS = $(SRC:yang/%.yang=../load-dir/%.fxs)

NCSC_DEVICE_DIR = ./ncsc-out

all: $(FXS)
	cd ../netsim && $(MAKE) $@

# Junos doesn't have any Yang config false data, thus
# we can skip the statistics
$(NCSC_DEVICE_DIR)/modules/fxs/%.fxs: yang/%.yang
	rm -f $@ || true
	mkdir -p $(NCSC_DEVICE_DIR)
	$(NCSC) --use-description --ncs-compile-module $< \
		--ncs-device-dir $(NCSC_DEVICE_DIR) \
		--ncs-skip-statistics \
		--ncs-device-type netconf | 2>&1 \
	  grep -v 'has no actionpoint' || true

../load-dir/%.fxs: $(NCSC_DEVICE_DIR)/modules/fxs/%.fxs
	rm -f $@
	ln -s ../src/$< $@

.PRECIOUS: $(NCSC_DEVICE_DIR)/modules/fxs/%.fxs

clean:
	rm -f $(FXS)
	rm -rf $(NCSC_DEVICE_DIR)
	cd ../netsim && $(MAKE) clean


.PHONY: all clean

include $(NCS_DIR)/src/ncs/build/include.ncs.mk
