# Convert GeoJSON to OSM XML format

To run, get the fat jar (currently you need to build this yourself) in this repository and then 

    java -cp geojson2osm-all-0.1.0.jar ch.poole.osm.geojson2osm.Convert -i ...
    
### Usage

    -i,--input <arg>    input GeoJSON file, default: standard in
    -o,--output <arg>   output OSM XML file, default: standard out
    -u,--upload         generate a JOSM specific attribute that will allow uploading the data

## Caveats

- Currently this is just a quick hack.
- It will only convert Point and LineString geometries.
- It will punt on LineString Features with more than 2000 vertices
- OSM way nodes are de-duplicated (this should probably be an option)

All of the above is fixable, it just hasn't been done yet.