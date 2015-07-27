package com.matecat.converter.core;

import com.matecat.converter.core.encoding.ICUEncodingDetector;
import com.matecat.converter.core.encoding.IEncodingDetector;
import com.matecat.converter.core.format.Format;
import com.matecat.converter.core.format.FormatNotSupportedException;
import com.matecat.converter.core.format.converters.AbstractFormatConverter;
import com.matecat.converter.core.format.converters.Converters;
import com.matecat.converter.core.okapiclient.OkapiClient;
import com.matecat.converter.core.okapiclient.OkapiPack;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Xliff Processor
 *
 * Processor of Xliff files, allowing:
 *  1. Extraction of the embedded pack (original file and manifest)
 *  2. Extraction of source and target languages
 */
public class XliffProcessor {

    // File we are processing
    private File xlf;

    // Embedded pack
    private OkapiPack pack;

    // Inner properties
    private Format originalFormat;
    private Locale sourceLanguage, targetLanguage;


    /**
     * Construct the processor given the XLF
     * @param xlf Xliff file we are going to process
     */
    public XliffProcessor(final File xlf) {

        // Check that the input file is not null
        if (xlf == null  ||  !xlf.exists()  ||  xlf.isDirectory())
            throw new IllegalArgumentException("The input file does not exist");

        // Check that the file is an .xlf
        if (!FilenameUtils.getExtension(xlf.getName()).equals("xlf"))
            throw new IllegalArgumentException("The input file is not a .xlf file");

        // Save the xlf
        this.xlf = xlf;

    }


    /**
     * Get source language
     * @return Source language
     */
    public Locale getSourceLanguage() {
        if (sourceLanguage == null)
            extractLanguages();
        return sourceLanguage;
    }


    /**
     * Get target language
     * @return Target language
     */
    public Locale getTargetLanguage() {
        if (targetLanguage == null)
            extractLanguages();
        return targetLanguage;
    }


    /**
     * Extract language from the XLF
     */
    private void extractLanguages() {
        try {
            // Parse the XML document
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            Document document = documentBuilder.parse(xlf);
            Element firstFile = (Element) document.getDocumentElement().getElementsByTagName("file").item(0);

            // Extract the languages
            this.sourceLanguage = new Locale(firstFile.getAttribute("source-language"));
            this.targetLanguage = new Locale(firstFile.getAttribute("target-language"));
        }
        catch (ParserConfigurationException | SAXException | IOException e) {
            throw new RuntimeException("It was not possible to extract the source and target languages. Corrupted xlf?");
        }
    }


    /**
     * Get the original file embedded into the XLF
     * @return Original file
     */
    public File getOriginalFile() {

        // Reconstruct the pack
        if (pack == null)
            reconstructPack();

        // Get the original file
        File originalFile = pack.getOriginalFile();

        // If it does not have its original format, try to convert it
        if (Format.getFormat(originalFile) != originalFormat) {
            try {
                originalFile = new Converters().convert(originalFile, originalFormat);
            }
            catch (FormatNotSupportedException ignored)  {}
        }

        // Return it
        return originalFile;

    }


    /**
     * Get the derived file
     * This is produced using the original file, the manifest and the XLF
     * @return Derived file
     */
    public File getDerivedFile() {

        // Reconstruct the pack
        if (pack == null)
            reconstructPack();

        // Generate the derived file
        File derivedFile = OkapiClient.generateDerivedFile(pack);

        // If it does not have its original format, try to convert it
        if (Format.getFormat(derivedFile) != originalFormat) {
            try {
                derivedFile = new Converters().convert(derivedFile, originalFormat);
            }
            catch (FormatNotSupportedException ignored)  {}
        }

        // Return it
        return derivedFile;

    }





    private void reconstructPack() {

        try {

            // Output folder
            File packFolder = new File(xlf.getParentFile().getPath() + File.separator + "pack");
            if (packFolder.exists())
                FileUtils.cleanDirectory(packFolder);
            else
                packFolder.mkdir();

            // Parse the XML document
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            Document document = documentBuilder.parse(xlf);
            Element root = document.getDocumentElement();

            // Reconstruct the original file
            Element fileElement = (Element) root.getFirstChild();
            reconstructOriginalFile(packFolder, fileElement);

            // Extract the original format
            extractOriginalFormat(fileElement);

            // Extract the languages
            this.sourceLanguage = new Locale(fileElement.getAttribute("source-language"));
            this.targetLanguage = new Locale(fileElement.getAttribute("target-language"));

            // Reconstruct the manifest
            Element manifestElement = (Element) fileElement.getNextSibling();
            reconstructManifest(packFolder, manifestElement);

            // Reconstruct the original xlf
            reconstructOriginalXlf(packFolder, document, fileElement, manifestElement);

            // Generate the pack (which will check the extracted files)
            this.pack = new OkapiPack(packFolder);

        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new RuntimeException("It was not possible to reconstruct the pack from the Xliff");
        }

    }


    private void extractOriginalFormat(Element fileElement) {

        // Try to obtain the format from the data type
        try {
            String filename = fileElement.getAttribute("original");
            this.originalFormat = Format.getFormat(filename);
        }
        catch (Exception e1) {
            throw new RuntimeException("The encoded file has no extension");
        }

    }


    private String getFilename(Element fileElement) {

        // Filename
        String filename = fileElement.getAttribute("original");

        // Replace the extension of the file for the one it was converted to
        // Datatype structure is:  datatype="x-{FORMAT (after conversions)}"
        try {
            String datatype = fileElement.getAttribute("datatype");
            String convertedExtension = datatype.substring(2);
            filename = FilenameUtils.getBaseName(filename) + "." + convertedExtension;
        }
        catch (Exception ignore) {}

        // Return it
        return filename;

    }

    private void reconstructOriginalFile(File packFolder, Element fileElement) {

        try {

            // Filename
            String filename = getFilename(fileElement);

            // Contents
            Element internalFileElement = (Element) fileElement.getFirstChild().getFirstChild().getFirstChild();
            String encodedFile = internalFileElement.getTextContent();
            byte[] originalFileBytes = Base64.getDecoder().decode(encodedFile);
            //String form = internalFileElement.getAttribute("form");

            // Create original folder
            File originalFolder = new File(packFolder.getPath() + File.separator + OkapiPack.ORIGINAL_DIRECTORY_NAME);
            if (originalFolder.exists())
                FileUtils.cleanDirectory(originalFolder);
            else
                originalFolder.mkdir();

            // Reconstruct the original file
            File originalFile = new File(originalFolder.getPath() + File.separator + filename);
            originalFile.createNewFile();
            FileUtils.writeByteArrayToFile(originalFile, originalFileBytes);

        }
        catch (Exception e) {
            throw new RuntimeException("It was not possible to reconstruct the original file");
        }
    }

    private void reconstructManifest(File packFolder, Element manifestElement) {

        try {

            // Check that it's the manifest
            if (!manifestElement.getAttribute("original").equals(OkapiPack.MANIFEST_FILENAME))
                throw new RuntimeException("The xlf is corrupted: it does not contain a manifest");

            // Manifest contents
            String encodedManifest = manifestElement.getFirstChild().getFirstChild().getFirstChild().getTextContent();
            byte[] manifestBytes = Base64.getDecoder().decode(encodedManifest);

            // Reconstruct the manifest file
            File manifestFile = new File(packFolder.getPath() + File.separator + OkapiPack.MANIFEST_FILENAME);
            manifestFile.createNewFile();
            FileUtils.writeByteArrayToFile(manifestFile, manifestBytes);

        }
        catch (Exception e) {
            throw new RuntimeException("It was not possible to reconstruct the manifest file");
        }
    }

    private void reconstructOriginalXlf(File packFolder, Document document, Element fileElement, Element manifestElement) {

        try {

            // Get root
            Element root = document.getDocumentElement();

            // Filename
            String filename = getFilename(fileElement);

            // Obtain the original xlf
            root.removeChild(fileElement);
            root.removeChild(manifestElement);

            // Normalize document
            document.normalizeDocument();

            // Create work folder
            File workFolder = new File(packFolder.getPath() + File.separator + OkapiPack.WORK_DIRECTORY_NAME);
            if (workFolder.exists())
                FileUtils.cleanDirectory(workFolder);
            else
                workFolder.mkdir();

            // Save the file
            String xlfOutputPath = workFolder.getPath() + File.separator + filename + ".xlf";
            Transformer transformer = TransformerFactory.newInstance().newTransformer();

            DOMSource domSource = new DOMSource(document);
            StreamResult streamResult = new StreamResult(xlfOutputPath);
            transformer.transform(domSource, streamResult);

        } catch (TransformerException | IOException e) {
            e.printStackTrace();
        }
    }

}