#!/bin/bash

cat <<EOF
Building configuration...



Current configuration : 1747 bytes

!

! Last configuration change at 07:44:06 UTC Fri Jan 30 2015 by Hakan

!

version 15.1

service timestamps debug datetime msec

service timestamps log datetime msec

no platform punt-keepalive disable-kernel-core

!

hostname MYHOSTNAME

!

boot-start-marker

boot system flash bootflash:asr1001-universalk9.V151_3_S3_SR620260181_2.bin

boot-end-marker

!

!

vrf definition Mgmt-intf

 !

 address-family ipv4

 exit-address-family

 !

 address-family ipv6

 exit-address-family

!

EOF
