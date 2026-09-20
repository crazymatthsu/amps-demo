
- support QueueChannel : inbound data source can send messages to a queue channel, which can be consumed by a downstream  
  component. This allows for decoupling of the data source and the consumer, enabling asynchronous processing and 
  buffering of messages. This will also require async acknowledgement of messages to the data source like Kafka. 
  data source like tcp, jdbc, etc that doesn't require acknowledgement , the async ack will be a no-op.

- 'source-file' : load string records from a file and tail the file for updates 
- 
- 
[verfy if below has been implemented in the amps connector]
- Need to support AMPS HAClient to publish messages to AMPS when server failover from primary to secondary. 
  This will require the AMPS HAClient to be configured with the primary and secondary server addresses, 
  and the connector to handle failover events and reconnect to the new primary server. 
- for AMPS HAClient publisher, need to have option to configure PublishStore to prevent message loss during failover. 
  This will require the connector to support configuring the PublishStore 
  with appropriate settings, such as the maximum number of messages to store and the duration to retain them.
- 