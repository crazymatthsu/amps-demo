
### this submodule will create a cache library that will use AMPS as its remote distributed cache persistent store
#### cache library features 
- simple java Map as cache
- support Map<String, ?> 
- support Map<String, Map<String, ?>>
- MapLoader : hydrate local cache from AMPS at start up 
- MapStore : persist cache in AMPS sow topic 

#### cache library with AMPS integration 
- use json as on the wire message format 
- use AMPS sow topics for key value cache 
- propose a solution for Map of Map data structure 
  - local cache has a Map<String, Map<String, ?>> : how to persist this data structure in AMPS ? 
  - any alternative solution to store Map<String, Map<String, ?>>  

#### use AMPS as remote distributed cache persistent store 
- cache library provide local cache storage 
- AMPS provide remote storage 
- If the java process restart or failover to a different machine, the java process will recover its cache from AMPS 
- if local cache doesn't have the data, it can query AMPS to fetch the data 
- provide a amps-config example for this demo project

#### demo testing 
- provide script to run AMPS in podman 
- integration test of local cache and AMPS 
- query AMPS to get data 
- restart process to recover local cache from AMPS 
