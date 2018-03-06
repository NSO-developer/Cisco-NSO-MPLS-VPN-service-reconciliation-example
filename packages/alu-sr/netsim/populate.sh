#!/bin/bash

confd_cli -C --user=admin --groups=admin<<EOF
config t
load merge initial.cfg
commit
exit
EOF
