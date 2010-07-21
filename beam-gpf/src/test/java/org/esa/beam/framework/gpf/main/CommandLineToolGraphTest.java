/*
 * Copyright (C) 2010 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.beam.framework.gpf.main;

import com.bc.ceres.binding.dom.DomElement;
import com.sun.media.jai.util.SunTileScheduler;

import junit.framework.TestCase;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.graph.Graph;
import org.esa.beam.framework.gpf.graph.GraphException;
import org.esa.beam.framework.gpf.graph.GraphIO;
import org.esa.beam.framework.gpf.graph.Node;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import javax.media.jai.JAI;
import javax.media.jai.TileScheduler;

public class CommandLineToolGraphTest extends TestCase {
    private GraphCommandLineContext context;
    private CommandLineTool clTool;
    private TileScheduler jaiTileScheduler;


    @Override
    protected void setUp() throws Exception {
        context = new GraphCommandLineContext();
        clTool = new CommandLineTool(context);
        JAI jai = JAI.getDefaultInstance();
        jaiTileScheduler = jai.getTileScheduler();
        SunTileScheduler tileScheduler = new SunTileScheduler();
        tileScheduler.setParallelism(Runtime.getRuntime().availableProcessors());
        jai.setTileScheduler(tileScheduler);
    }
    
    @Override
    protected void tearDown() throws Exception {
        JAI.getDefaultInstance().setTileScheduler(jaiTileScheduler);
    }

    public void testGraphUsageMessage() throws Exception {
        final String[] args = new String[]{"-h", "graph.xml"};

        clTool.run(args);

        final String message = context.m;
        assertNotNull(message);
        assertTrue(message.contains("Usage:"));
        assertTrue(message.contains("Source Options:"));
        assertTrue(message.contains("sourceProduct1"));
        assertTrue(message.contains("First source product"));
        assertTrue(message.contains("sourceProduct2"));
        assertTrue(message.contains("Parameter Options:"));
        assertTrue(message.contains("threshold"));
        assertTrue(message.contains("Threshold value"));
        assertTrue(message.contains("expression"));

        //System.out.println(message);
    }

    public void testGraphOnly() throws Exception {
        testGraph(new String[]{"graph.xml"},
                  3,
                  "g=graph.xml;e=chain1;",
                  "${sourceProduct}", null,
                  "${sourceProduct2}", null,
                  "WriteProduct$node2",
                  "target.dim",
                  "BEAM-DIMAP",
                  "${threshold}",
                  "${expression}"
        );
    }

    public void testGraphWithParameters() throws Exception {
        testGraph(new String[]{"graph.xml", "-Pexpression=a+b/c", "-Pthreshold=2.5"},
                  3,
                  "g=graph.xml;e=chain1;",
                  "${sourceProduct}", null,
                  "${sourceProduct2}", null,
                  "WriteProduct$node2", "target.dim", "BEAM-DIMAP", "2.5",
                  "a+b/c"
        );
    }

    public void testGraphWithParametersAndSourceArgs() throws Exception {
        testGraph(new String[]{"graph.xml", "-Pexpression=a+b/c", "-Pthreshold=2.5", "ernie.dim", "idefix.dim"},
                  5,
                  "g=graph.xml;e=chain1;",
                  "ReadProduct$0", "ernie.dim",
                  "ReadProduct$1", "idefix.dim",
                  "WriteProduct$node2", "target.dim", "BEAM-DIMAP", "2.5",
                  "a+b/c"
        );
    }

    public void testGraphWithParametersAndSourceOptions() throws Exception {
        testGraph(new String[]{
                "graph.xml",
                "-Pexpression=a+b/c",
                "-Pthreshold=2.5",
                "-SsourceProduct=ernie.dim",
                "-SsourceProduct2=idefix.dim"},
                  5,
                  "g=graph.xml;e=chain1;",
                  "ReadProduct$0",
                  "ernie.dim",
                  "ReadProduct$1",
                  "idefix.dim",
                  "WriteProduct$node2",
                  "target.dim",
                  "BEAM-DIMAP",
                  "2.5",
                  "a+b/c"
        );
    }

    public void testGraphWithParametersFileOption() throws Exception {
        testGraph(new String[]{
                "graph.xml",
                "-p",
                "paramFile.properties",
                "-SsourceProduct=ernie.dim",
                "-SsourceProduct2=idefix.dim"},
                  5,
                  "g=graph.xml;e=chain1;",
                  "ReadProduct$0",
                  "ernie.dim",
                  "ReadProduct$1",
                  "idefix.dim",
                  "WriteProduct$node2",
                  "target.dim",
                  "BEAM-DIMAP",
                  "-0.5125",
                  "sqrt(x*x + y*y)"
        );
    }

    public void testGraphWithParametersFileOptionIsOverwrittenByOption() throws Exception {
        testGraph(new String[]{
                "graph.xml",
                "-p",
                "paramFile.properties",
                "-Pexpression=atan(y/x)",
                "-SsourceProduct=ernie.dim",
                "-SsourceProduct2=idefix.dim"},
                  5,
                  "g=graph.xml;e=chain1;",
                  "ReadProduct$0",
                  "ernie.dim",
                  "ReadProduct$1",
                  "idefix.dim",
                  "WriteProduct$node2",
                  "target.dim",
                  "BEAM-DIMAP",
                  "-0.5125",
                  "atan(y/x)"
        );

    }


    private void testGraph(String[] args,
                           int expectedNodeCount,
                           String expectedLog,
                           String expectedSourceNodeId1,
                           String expectedSourceFilepath1,
                           String expectedSourceNodeId2,
                           String expectedSourceFilepath2,
                           String expectedTargetNodeId,
                           String expectedTargetFilepath,
                           String expectedTargetFormat,
                           String expectedThreshold,
                           String expectedExpression) throws Exception {
        clTool.run(args);

        assertEquals(expectedLog, context.logString);

        Graph executedGraph = context.executedGraph;
        assertNotNull(executedGraph);
        assertEquals(expectedNodeCount, executedGraph.getNodeCount());

        Node node1 = executedGraph.getNode("node1");
        assertEquals(expectedSourceNodeId1, node1.getSource(0).getSourceNodeId());
        assertEquals(expectedThreshold, node1.getConfiguration().getChild("threshold").getValue());

        Node node2 = executedGraph.getNode("node2");
        assertEquals("node1", node2.getSource(0).getSourceNodeId());
        assertEquals(expectedSourceNodeId2, node2.getSource(1).getSourceNodeId());
        assertEquals(expectedExpression, node2.getConfiguration().getChild("expression").getValue());

        if (expectedSourceFilepath1 != null) {
            Node generatedReaderNode1 = executedGraph.getNode(expectedSourceNodeId1);
            assertNotNull(generatedReaderNode1);
            assertEquals(expectedSourceFilepath1, generatedReaderNode1.getConfiguration().getChild("file").getValue());
        }

        if (expectedSourceFilepath2 != null) {
            Node generatedReaderNode2 = executedGraph.getNode(expectedSourceNodeId2);
            assertNotNull(generatedReaderNode2);
            assertEquals(expectedSourceFilepath2, generatedReaderNode2.getConfiguration().getChild("file").getValue());
        }

        Node generatedWriterNode = executedGraph.getNode(expectedTargetNodeId);
        assertNotNull(generatedWriterNode);
        assertEquals("node2", generatedWriterNode.getSource(0).getSourceNodeId());

        DomElement parameters = generatedWriterNode.getConfiguration();
        assertNotNull(parameters);
        assertNotNull(expectedTargetFilepath, parameters.getChild("file").getValue());
        assertNotNull(expectedTargetFormat, parameters.getChild("formatName").getValue());
    }


    private static class GraphCommandLineContext implements CommandLineContext {
        public String logString;
        private int readProductCounter;
        private int writeProductCounter;
        public Graph executedGraph;
        private String m = "";

        public GraphCommandLineContext() {
            logString = "";
        }

        public Product readProduct(String productFilepath) throws IOException {
            logString += "s" + readProductCounter + "=" + productFilepath + ";";
            readProductCounter++;
            return new Product("P", "T", 10, 10);
        }

        public void writeProduct(Product targetProduct, String filePath, String formatName) throws IOException {
            logString += "t" + writeProductCounter + "=" + filePath + ";";
            writeProductCounter++;
        }

        public Graph readGraph(String filepath, Map<String, String> parameterMap) throws IOException, GraphException {

            logString += "g=" + filepath + ";";

            String xml =
                    "<graph id=\"chain1\">" +
                            "<version>1.0</version>\n" +
                            "<header>\n" +
                            "<target refid=\"node2\"/>\n" +
                            "<source name=\"sourceProduct1\" description=\"First source product\"/>\n" +
                            "<source name=\"sourceProduct2\"/>\n" +
                            "<parameter name=\"threshold\" type=\"double\" description=\"Threshold value\"/>\n" +
                            "<parameter name=\"expression\" type=\"String\"/>\n" +
                            "</header>\n" +
                            "<node id=\"node1\">" +
                            "  <operator>org.esa.beam.framework.gpf.TestOps$Op2$Spi</operator>\n" +
                            "  <sources>\n" +
                            "    <input>${sourceProduct}</input>\n" +
                            "  </sources>\n" +
                            "  <parameters>\n" +
                            "    <threshold>${threshold}</threshold>\n" +
                            "  </parameters>\n" +
                            "</node>" +
                            "<node id=\"node2\">" +
                            "  <operator>org.esa.beam.framework.gpf.TestOps$Op3$Spi</operator>\n" +
                            "  <sources>\n" +
                            "    <input1 refid=\"node1\"/>\n" +
                            "    <input2>${sourceProduct2}</input2>\n" +
                            "  </sources>\n" +
                            "  <parameters>\n" +
                            "    <expression>${expression}</expression>\n" +
                            "  </parameters>\n" +
                            "</node>" +
                            "</graph>";

            return GraphIO.read(new StringReader(xml), parameterMap);
        }

        public void executeGraph(Graph graph) throws GraphException {
            logString += "e=" + graph.getId() + ";";
            executedGraph = graph;
        }


        public Product createOpProduct(String opName, Map<String, Object> parameters, Map<String, Product> sourceProducts) throws OperatorException {
            fail("did not expect to come here");
            return null;
        }

        public Map<String, String> readParameterFile(String propertiesFilepath) throws IOException {
            HashMap<String, String> hashMap = new HashMap<String, String>();
            hashMap.put("expression", "sqrt(x*x + y*y)");
            hashMap.put("threshold", "-0.5125");
            return hashMap;
        }

        public void print(String m) {
            this.m += m;
        }
    }
}
