#!/bin/bash

cat <<EOF
Building configuration...
!! IOS XR Configuration 4.2.3
!! Last configuration change at Sun Oct 12 07:41:47 2014 by xxx
!
version 12.2
no service pad
service timestamps debug datetime msec localtime show-timezone
service timestamps log datetime msec localtime show-timezone
service password-encryption
service pt-vty-logging
service counters max age 10
no service dhcp
service unsupported-transceiver
!
hostname MYHOSTNAME
!
!
logging buffered informational
no logging console
!
aaa new-model

end

EOF
