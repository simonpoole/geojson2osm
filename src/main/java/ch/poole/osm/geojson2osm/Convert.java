package ch.poole.osm.geojson2osm;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mapbox.geojson.CoordinateContainer;
import com.mapbox.geojson.Feature;
import com.mapbox.geojson.FeatureCollection;
import com.mapbox.geojson.Geometry;
import com.mapbox.geojson.Point;

public class Convert {

    // command line
    private static final String OUTPUT_ARG = "output";
    private static final String INPUT_ARG  = "input";
    private static final String UPLOAD_ARG = "upload";

    // OSM
    private static final String VALUE_ATTR     = "v";
    private static final String KEY_ATTR       = "k";
    private static final String TAG_ELEMENT    = "tag";
    private static final String LON_ATTR       = "lon";
    private static final String LAT_ATTR       = "lat";
    private static final String NODE_ELEMENT   = "node";
    private static final String REF_ATTRIBUTE  = "ref";
    private static final String ND_ELEMENT     = "nd";
    private static final String ID_ATTR        = "id";
    private static final String WAY_ELEMENT    = "way";
    private static final String GENERATOR_ATTR = "generator";
    private static final String OSM_ELEMENT    = "osm";
    private static final String VERSION        = "version";
    private static final String VERSION_0_6    = "0.6";
    private static final String UPLOAD         = "upload";
    private static final String NEVER          = "never";
    private static final int    MAX_WAY_NODES  = 2000;

    // GEOJSON
    private static final String FEATURE_COLLECTION = "FeatureCollection";
    private static final String FEATURE            = "Feature";
    private static final String FEATURES           = "features";
    private static final String BBOX               = "bbox";
    private static final String POINT              = "Point";
    private static final String MULTIPOINT         = "MultiPoint";
    private static final String LINESTRING         = "LineString";
    private static final String MULTILINESTRING    = "MultiLineString";
    private static final String POLYGON            = "Polygon";
    private static final String MULTIPOLYGON       = "MultiPolygon";
    private static final String GEOMETRYCOLLECTION = "GeometryCollection";

    private long nodeId     = 0;
    private long wayId      = 0;
    private long relationId = 0;

    private final HashMap<Point, Long>   wayNodes = new HashMap<>();
    private final HashMap<Long, Feature> nodes    = new HashMap<>();
    private final HashMap<Long, Feature> ways     = new HashMap<>();

    /**
     * Convert GeoJSON from an input stream to OSM
     * 
     * @param is input
     * @param os output
     * @param set the upload flag to this
     */
    private void convert(@NotNull InputStream is, @NotNull OutputStream os, boolean upload) {

        BufferedReader in = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        try {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[4096];
            for (int numRead; (numRead = in.read(buffer, 0, buffer.length)) > 0;) {
                builder.append(buffer, 0, numRead);
            }

            final String json = builder.toString();
            FeatureCollection fc = FeatureCollection.fromJson(json);
            List<Feature> features = fc.features();
            if (features != null) {
                for (Feature f : features) {
                    collectElement(f);
                }
            } else {
                // Retrying as Feature
                Feature f = Feature.fromJson(json);
                if (f != null) {
                    collectElement(f);
                } else {
                    System.err.println("Input could not be processed");
                    return;
                }
            }
            XmlSerializer serializer = XmlPullParserFactory.newInstance().newSerializer();
            serializer.setOutput(os, StandardCharsets.UTF_8.name());
            serializer.startDocument(StandardCharsets.UTF_8.name(), null);
            serializer.startTag(null, OSM_ELEMENT);
            serializer.attribute(null, GENERATOR_ATTR, "ch.poole.osm.geojson2osm");
            serializer.attribute(null, VERSION, VERSION_0_6);
            serializer.attribute(null, UPLOAD, upload ? Boolean.toString(true) : NEVER);

            // output nodes
            for (Entry<Long, Feature> e : nodes.entrySet()) {
                outputNode(serializer, e.getKey(), e.getValue());
            }
            // output way nodes
            for (Entry<Point, Long> e : wayNodes.entrySet()) {
                outputNode(serializer, e.getValue(), e.getKey());
            }
            // output ways
            for (Entry<Long, Feature> e : ways.entrySet()) {
                outputWay(serializer, e.getKey(), e.getValue());
            }
            serializer.endTag(null, OSM_ELEMENT);
            serializer.endDocument();
        } catch (OutOfMemoryError oom) {
            System.err.println("Input could not be processed " + oom.getMessage());
        } catch (com.google.gson.JsonSyntaxException jsex) {
            System.err.println("Input could not be processed " + jsex.getMessage());
        } catch (Exception e) {
            System.err.println("Input could not be processed " + e.getMessage());
        }
    }

    /**
     * Add Feature f to the data structures
     * 
     * @param f the Feature to add
     */
    private void collectElement(@NotNull Feature f) {
        Geometry g = f.geometry();
        switch (g.type()) {
        case POINT:
            nodes.put(--nodeId, f);
            break;
        case LINESTRING:
            for (Point p : ((CoordinateContainer<List<Point>>) g).coordinates()) { // NOSONAR
                if (!wayNodes.containsKey(p)) {
                    wayNodes.put(p, --nodeId);
                }
                ways.put(--wayId, f);
            }
            break;
        default:
            System.err.println(g.type() + " is unsupported");
        }
    }

    /**
     * 
     * @param serializer the XmlSerializer
     * @param id the OSM id
     * @param f the corresponding GeoJSON Feature
     * @throws IOException if we can't write
     */
    private void outputWay(@NotNull XmlSerializer serializer, @NotNull Long id, @NotNull Feature f) throws IOException {
        final List<Point> points = ((CoordinateContainer<List<Point>>) f.geometry()).coordinates(); // NOSONAR
        if (points.size() > MAX_WAY_NODES) {
            System.err.println("Way too long " + points.size() + " nodes");
        } else {
            outputWay(serializer, id, f, points);
        }
    }

    /**
     * Output a way
     * 
     * @param serializer the XmlSerializer
     * @param id the OSM id
     * @param f the corresponding GeoJSON Feature
     * @param points
     * @throws IOException if we can't write
     */
    private void outputWay(@NotNull XmlSerializer serializer, long id, @NotNull Feature f, @NotNull final List<Point> points) throws IOException {
        serializer.startTag(null, WAY_ELEMENT);
        serializer.attribute(null, ID_ATTR, Long.toString(id));
        tags(serializer, f.properties());
        for (Point p : points) { // NOSONAR
            serializer.startTag(null, ND_ELEMENT);
            serializer.attribute(null, REF_ATTRIBUTE, Long.toString(wayNodes.get(p)));
            serializer.endTag(null, ND_ELEMENT);
        }
        serializer.endTag(null, WAY_ELEMENT);
    }

    /**
     * Output a tagged Node
     * 
     * @param serializer the XmlSerializer
     * @param id the OSM id
     * @param f the corresponding GeoJSON Feature
     * @throws IOException if we can't write
     */
    private void outputNode(@NotNull XmlSerializer serializer, Long id, Feature f) throws IOException {
        serializer.startTag(null, NODE_ELEMENT);
        nodeAttributes(serializer, id, ((Point) f.geometry()));
        tags(serializer, f.properties());
        serializer.endTag(null, NODE_ELEMENT);
    }

    /**
     * Output an untagged Node
     * 
     * @param serializer the XmlSerializer
     * @param id the OSM id
     * @param point a Point
     * @throws IOException if we can't write
     */
    private void outputNode(@NotNull XmlSerializer serializer, @NotNull Long id, @NotNull Point point) throws IOException {
        serializer.startTag(null, NODE_ELEMENT);
        nodeAttributes(serializer, id, point);
        serializer.endTag(null, NODE_ELEMENT);
    }

    /**
     * Add standard node attributes
     * 
     * @param serializer the XmlSerializer
     * @param id the OSM id
     * @param point the Point object
     * @throws IOException if we can't write
     */
    private void nodeAttributes(@NotNull XmlSerializer serializer, @NotNull Long id, @NotNull Point point) throws IOException {
        serializer.attribute(null, ID_ATTR, Long.toString(id));
        serializer.attribute(null, LAT_ATTR, Double.toString(point.latitude()));
        serializer.attribute(null, LON_ATTR, Double.toString(point.longitude()));
    }

    /**
     * Add OSM tags
     * 
     * @param serializer the XmlSerializer
     * @param properties the properties of the GeoJSON object
     * @throws IOException if we can't write
     */
    private void tags(@NotNull XmlSerializer serializer, @Nullable JsonObject properties) throws IOException {
        if (properties != null) {
            for (String key : properties.keySet()) {
                JsonElement e = properties.get(key);
                if (!e.isJsonNull() && e.isJsonPrimitive()) {
                    serializer.startTag(null, TAG_ELEMENT);
                    serializer.attribute(null, KEY_ATTR, key);
                    serializer.attribute(null, VALUE_ATTR, e.getAsString());
                    serializer.endTag(null, TAG_ELEMENT);
                }
            }
        }
    }

    /**
     * Default main
     * 
     * @param args command line args
     */
    public static void main(String[] args) {
        // defaults
        InputStream is = System.in;
        OutputStream os = System.out; // NOSONAR
        boolean uploadFlag = false;
        try {
            os = System.out; // NOSONAR

            // arguments
            Option inputFile = Option.builder("i").longOpt(INPUT_ARG).hasArg().desc("input geojson file, default: standard in").build();

            Option outputFile = Option.builder("o").longOpt(OUTPUT_ARG).hasArg().desc("output .osm file, default: standard out").build();

            Option upload = Option.builder("u").longOpt(UPLOAD_ARG).desc("set upload flag to true, default: false").build();

            Options options = new Options();

            options.addOption(inputFile);
            options.addOption(outputFile);
            options.addOption(upload);

            CommandLineParser parser = new DefaultParser();
            try {
                // parse the command line arguments
                CommandLine line = parser.parse(options, args);
                if (line.hasOption(INPUT_ARG)) {
                    // initialise the member variable
                    String input = line.getOptionValue(INPUT_ARG);
                    is = new FileInputStream(input);
                }
                if (line.hasOption(OUTPUT_ARG)) {
                    String output = line.getOptionValue(OUTPUT_ARG);
                    os = new FileOutputStream(output);
                }
                uploadFlag = line.hasOption(UPLOAD_ARG);
            } catch (ParseException exp) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp("Convert", options);
                return;
            } catch (FileNotFoundException e) {
                System.err.println("File not found: " + e.getMessage());
                return;
            }

            new Convert().convert(is, os, uploadFlag);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                // NOSONAR
            }
            try {
                if (os != null) {
                    os.close();
                }
            } catch (IOException e) {
                // NOSONAR
            }
        }
    }
}
