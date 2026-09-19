

- for FIX42 messages, create a demo program that will do delta publish to AMPS 
  - for 35=D, order message, use tag 9000 as the parent order id field, if set, then this is a child order. If not set, then this is a parent order. 
  - in the mock FIX messages, also include parent child relationship with tag 9000, and also include tag 11,41,37,17 for id chaining.
- on AMPS topic setup  
  - add Module key-chaining with library libamps_id_chaining_key_generator.so 
  - create below SOW topics , MessageType = fix
    - sow/parent/orders : 
      - Module : key-chaining
      - key: /11, /41
    - sow/parent/orders_audit
      - key: /11
    - sow/child/orders
        - Module : key-chaining
        - key: /11, /41
    - sow/child/orders_audit
      - key: /11
    - sow/parent/execs 
        - key: /37
    - sow/parent/execs_audit
      - key : /17
    - sow/parent/rejects
      - key : /11 
- on AMPS FIX delta publisher side
    - use spring boot application 
    - create sample FIX42 messages with various 35=D,G,F,8,9 mock messages with as many fields as possible to simulate real-world scenarios
    - id chaining (tag 11,41,37,17) needs to be correct in chained linked mock FIX42 messages 
    - create a FIX message delta publisher which will take a config file with below 
      - for 35=D, publish the full FIX message 
        - topic : sow/parent/orders , and sow/parent/orders_audit
      - for 35=G, publish id fields (tag 35, 11, 41), timestamp tag 60 and changeable fields, make the changeable fields configurable in yml file, and the publisher will extract only those fields from the FIX message and publish to AMPS
       - topic : sow/parent/orders , and sow/parent/orders_audit 
      - for 35=F, publish id fields (tag 35, 11, 41), timestamp tag 60 
        - topic : sow/parent/orders , and sow/parent/orders_audit 
    - for 35=8 :
        - topic : sow/parent/execs , and sow/parent/execs_audit
        - for new ack, publish id fields tag 11, 41 , and order status tag 39, exec status tag 150, timestamp tag 60, make these fields configurable in yml file, and the publisher will extract only those fields from the FIX message and publish to AMPS
        - for partial fill, publish id fields tag 11, 41 , and order status tag 39, exec status tag 150, timestamp tag 60, and last px, last shares, cum qty, leaves qty, make these fields configurable in yml file, and the publisher will extract only those fields from the FIX message and publish to AMPS
        - for fully fill, publish id fields tag 11, 41 , and order status tag 39, exec status tag 150, timestamp tag 60, and last px, last shares, cum qty, leaves qty, make these fields configurable in yml file, and the publisher will extract only those fields from the FIX message and publish to AMPS
        - for cancel confirmed, done for day, publish id fields tag 11, 41 , and order status tag 39, exec status tag 150, timestamp tag 60, leaves qty, make these fields configurable in yml file, and the publisher will extract only those fields from the FIX message and publish to AMPS

- use slf4j logging in java code 
- create integration test to verify the delta publish works correctly and the SOW topics are updated as expected
- use local podman container for AMPS server for integration testing

#### 2026.09.18 
- add a new submodule 'amps-connectors' 
- mimic the package structure of '/Users/maojenhsu/ai-code/deephaven-fix42-dashboard/claude-code/dh-connectors' 
- under 'amps-connectors', add below sources  
  - 'source-tcp' : subscribe to a tcp port, read line by line string msg and publish to a AMPS topic. 
  - 'source-kafka' : subscribe to a kafka topic, configure deserializer to string , either json or FIX message, and publish to AMPS.
  - 'source-jdbc' : based on sql select query, publish json results to AMPS topic ( sow with key, or non-sow with transaction log topic)
  - 'source-hazelcast' : subscribe to a hazelcast topic, read line by line string msg and publish to a AMPS topic.
- after receiving messages from source, it needs to be able to transform the messages before publishing to AMPS topic. 
- it needs to be able to parse and extract fields from the messages and use the fields as key for SOW topic, or use the fields to filter messages before publishing to AMPS topic.
- instead of receive one message and send one message to AMPS, should we introduce spring integration to allow batch processing of messages from source and publish to AMPS topic in batch?

