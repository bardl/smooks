/*
 * Milyn - Copyright (C) 2006 - 2010
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License (version 2.1) as published by the Free Software
 *  Foundation.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 *  See the GNU Lesser General Public License for more details:
 *  http://www.gnu.org/licenses/lgpl.txt
 */

package org.milyn.ect;

import org.milyn.archive.Archive;
import org.milyn.assertion.AssertArgument;
import org.milyn.ect.formats.unedifact.UnEdifactSpecificationReader;
import org.milyn.edisax.util.EDIUtils;
import org.milyn.edisax.model.internal.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * EDI Convertion Tool.
 * <p/>
 * Takes the set of messages from an {@link EdiSpecificationReader} and generates
 * a Smooks EDI Mapping Model archive that can be written to a zip file or folder.
 * 
 * @author bardl
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
public class EdiConvertionTool {

    /**
     * Write an EDI Mapping Model configuration set from a UN/EDIFACT
     * specification.
     *
     * @param specification The UN/EDIFACT specification zip file.
     * @param modelSetOutStream The output zip stream for the generated EDI Mapping Model configuration set.
     * @param urn The URN for the EDI Mapping model configuration set.
     * @throws IOException Error writing Mapping Model configuration set.
     */
    public static void fromUnEdifactSpec(ZipInputStream specification, ZipOutputStream modelSetOutStream, String urn) throws IOException {
        try {
            fromSpec(new UnEdifactSpecificationReader(specification, true), modelSetOutStream, urn);
        } finally {
            specification.close();
        }
    }

    /**
     * Write an EDI Mapping Model configuration set from the specified EDI Specification Reader.
     * @param ediSpecificationReader The configuration reader for the EDI interchange configuration set.
     * @param modelSetOutStream The EDI Mapping Model output Stream.
     * @param urn The URN for the EDI Mapping model configuration set.
     * @throws IOException Error writing Mapping Model configuration set.
     */
    public static void fromSpec(EdiSpecificationReader ediSpecificationReader, ZipOutputStream modelSetOutStream, String urn) throws IOException {
        AssertArgument.isNotNull(ediSpecificationReader, "ediSpecificationReader");
        AssertArgument.isNotNull(modelSetOutStream, "modelSetOutStream");

        try {
            Archive archive = createArchive(ediSpecificationReader, urn);

            // Now output the generated archive...
            archive.toOutputStream(modelSetOutStream);
        } finally {
            modelSetOutStream.close();
        }
    }

    /**
     * Write an EDI Mapping Model configuration set from a UN/EDIFACT
     * specification.
     *
     * @param specification The UN/EDIFACT specification zip file.
     * @param modelSetOutFolder The output folder for the generated EDI Mapping Model configuration set.
     * @param urn The URN for the EDI Mapping model configuration set.
     * @throws IOException Error writing Mapping Model configuration set.
     */
    public static void fromUnEdifactSpec(ZipInputStream specification, File modelSetOutFolder, String urn) throws IOException {
        try {
            fromSpec(new UnEdifactSpecificationReader(specification, true), modelSetOutFolder, urn);
        } finally {
            specification.close();
        }
    }

    /**
     * Write an EDI Mapping Model configuration set from the specified EDI Specification Reader.
     * @param ediSpecificationReader The configuration reader for the EDI interchange configuration set.
     * @param modelSetOutFolder The output folder for the generated EDI Mapping Model configuration set.
     * @param urn The URN for the EDI Mapping model configuration set.
     * @throws IOException Error writing Mapping Model configuration set.
     */
    public static void fromSpec(EdiSpecificationReader ediSpecificationReader, File modelSetOutFolder, String urn) throws IOException {
        AssertArgument.isNotNull(ediSpecificationReader, "ediSpecificationReader");
        AssertArgument.isNotNull(modelSetOutFolder, "modelSetOutFolder");

        Archive archive = createArchive(ediSpecificationReader, urn);

        // Now output the generated archive...
        archive.toFileSystem(modelSetOutFolder);
    }

    private static Archive createArchive(EdiSpecificationReader ediSpecificationReader, String urn) throws IOException {
        Archive archive = new Archive();
        StringBuilder modelListBuilder = new StringBuilder();
        Set<String> messages = ediSpecificationReader.getMessageNames();
        StringWriter messageEntryWriter = new StringWriter();
        String pathPrefix = urn.replace(".", "_").replace(":", "/");

        for(String message : messages) {
            Edimap model = ediSpecificationReader.getMappingModel(message);
            String messageEntryPath = pathPrefix + "/" + message + ".xml";

            removeDuplicateSegments(model.getSegments());

            // Generate the mapping model for this message...
            messageEntryWriter.getBuffer().setLength(0);
            model.write(messageEntryWriter);

            // Add the generated mapping model to the archive...
            archive.addEntry(messageEntryPath, messageEntryWriter.toString());

            // Add this messages archive entry to the mapping model list file...
            modelListBuilder.append("/" + messageEntryPath);
            modelListBuilder.append("!" + model.getDescription().getName());
            modelListBuilder.append("!" + model.getDescription().getVersion());
            modelListBuilder.append("\n");
        }

        // Add the generated mapping model to the archive...
        archive.addEntry(EDIUtils.EDI_MAPPING_MODEL_ZIP_LIST_FILE, modelListBuilder.toString());

        // Add the model set URN to the archive...
        archive.addEntry(EDIUtils.EDI_MAPPING_MODEL_URN, urn);

        // Add an entry for the interchange properties...
        Properties interchangeProperties = ediSpecificationReader.getInterchangeProperties();
        ByteArrayOutputStream propertiesOutStream = new ByteArrayOutputStream();
        try {
            interchangeProperties.store(propertiesOutStream, "UN/EDIFACT Interchange Properties");
            propertiesOutStream.flush();
            archive.addEntry(EDIUtils.EDI_MAPPING_MODEL_INTERCHANGE_PROPERTIES_FILE, propertiesOutStream.toByteArray());
        } finally {
            propertiesOutStream.close();
        }

        return archive;
    }

    private static void removeDuplicateSegments(SegmentGroup segmentGroup) {
        if(segmentGroup instanceof Segment) {
            removeDuplicateFields(((Segment)segmentGroup).getFields()); 
        }

        List<SegmentGroup> segments = segmentGroup.getSegments();
        if(segments != null) {
            removeDuplicateMappingNodes(segments);
            for(SegmentGroup childSegmentGroup : segments) {
                removeDuplicateSegments(childSegmentGroup);
            }
        }
    }

    private static void removeDuplicateFields(List<Field> fields) {
        if(fields != null && !fields.isEmpty()) {
            // Remove the duplicates from the fields themselves...
            removeDuplicateMappingNodes(fields);

            // Drill down into the field components...
            for(Field field : fields) {
                removeDuplicateComponents(field.getComponents());
            }
        }
    }

    private static void removeDuplicateComponents(List<Component> components) {
        if(components != null && !components.isEmpty()) {
            // Remove the duplicates from the components themselves...
            removeDuplicateMappingNodes(components);

            // Remove duplicate sub components from each component...
            for(Component component : components) {
                removeDuplicateMappingNodes(component.getSubComponents());
            }
        }
    }

    private static void removeDuplicateMappingNodes(List mappingNodes) {
        if(mappingNodes == null || mappingNodes.isEmpty()) {
            return;
        }
        
        Set<String> nodeNames = getMappingNodeNames(mappingNodes);

        if(nodeNames.size() < mappingNodes.size()) {
            // There may be duplicates... find them and number them...
            for(String nodeName : nodeNames) {
                int nodeCount = getMappingNodeCount(mappingNodes, nodeName);
                if(nodeCount > 1) {
                    removeDuplicateMappingNodes(mappingNodes, nodeName);
                }
            }
        }
    }

    private static void removeDuplicateMappingNodes(List mappingNodes, String nodeName) {
        int tagIndex = 1;

        for(Object mappingNodeObj : mappingNodes) {
            MappingNode mappingNode = (MappingNode) mappingNodeObj;
            String xmlTag = mappingNode.getXmltag();

            if(xmlTag != null && xmlTag.equals(nodeName)) {
                mappingNode.setXmltag(xmlTag + MappingNode.INDEXED_NODE_SEPARATOR + tagIndex);
                tagIndex++;
            }
        }
    }

    private static Set<String> getMappingNodeNames(List mappingNodes) {
        Set<String> nodeNames = new LinkedHashSet<String>();

        for(Object mappingNode : mappingNodes) {
            String xmlTag = ((MappingNode) mappingNode).getXmltag();
            if(xmlTag != null) {
                nodeNames.add(xmlTag);
            }
        }

        return nodeNames;
    }

    private static int getMappingNodeCount(List mappingNodes, String nodeName) {
        int nodeCount = 0;

        for(Object mappingNode : mappingNodes) {
            String xmlTag = ((MappingNode) mappingNode).getXmltag();
            if(xmlTag != null && xmlTag.equals(nodeName)) {
                nodeCount++;
            }
        }

        return nodeCount;
    }
}
