#!/bin/bash
cd /home/ec2-user
java -cp loadbalancer.jar pt.ulisboa.tecnico.cnv.loadbalancer.LoadBalancer \
    >> /home/ec2-user/loadbalancer.log 2>&1