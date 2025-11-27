package com.atakmap.android.LoRaBridge.GenericMessage;

import com.siemens.ct.exi.core.EXIFactory;
import com.siemens.ct.exi.core.helpers.DefaultEXIFactory;
import com.siemens.ct.exi.main.api.sax.EXIResult;
import com.siemens.ct.exi.main.api.sax.EXISource;
import com.atakmap.coremap.xml.XMLUtils;

import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Result;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import java.io.*;

/**
 * ExiUtils
 *
 * Utility class providing conversion between XML and EXI (Efficient XML Interchange)
 * binary encoding. EXI significantly reduces message size which makes it suitable for
 * constrained radio environments such as LoRa.
 *
 * Features:
 *   • Safe XML parsing with hardened SAX settings (XXE protection)
 *   • Stateless static methods
 *   • Compatible with Siemens EXI library (EXIficient)
 *
 * Used by:
 *   - LoRaGenericCotConverter for encoding/decoding CoT events
 *   - CotSyncService during PHY <-> ATAK CoT synchronization
 */
public final class ExiUtils {

    /** Utility class: disallow instantiation */
    private ExiUtils() {}

    /**
     * Convert XML string into EXI-encoded byte array.
     *
     * Pipeline:
     *   XML string
     *      → SAX parsing
     *      → EXIResult output handler
     *      → EXI bytes
     *
     * This implementation follows the same principle used in Meshtastic,
     * which also parses CoT XML via SAX and forwards events into an EXIResult
     * so that EXIFactory can encode them into compact EXI binary form.
     *
     * @param xml Raw XML string to encode
     * @return EXI encoded byte array
     * @throws Exception If parsing or encoding fails
     */
    public static byte[] toExi(String xml) throws Exception {
        EXIFactory f = DefaultEXIFactory.newInstance();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        EXIResult exi = new EXIResult(f);
        exi.setOutputStream(os);

        // Hardened SAX parser configuration
        SAXParserFactory spf = SAXParserFactory.newInstance();
        try {
            spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            spf.setFeature("http://xml.org/sax/features/validation", false);
            spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Throwable ignored) {
            // Not all platforms implement all features; ignore safely
        }

        SAXParser p = spf.newSAXParser();
        XMLReader xr = p.getXMLReader();
        xr.setContentHandler(exi.getHandler());
        xr.parse(new InputSource(new StringReader(xml)));

        return os.toByteArray();
    }

    /**
     * Convert EXI-encoded bytes back into an XML string.
     *
     * Pipeline:
     *   EXI bytes
     *      → EXISource
     *      → Transformer
     *      → XML string
     *
     * @param exi Raw EXI binary message
     * @return Decoded XML
     * @throws Exception If decoding fails
     */
    public static String fromExi(byte[] exi) throws Exception {
        EXIFactory f = DefaultEXIFactory.newInstance();
        InputSource is = new InputSource(new ByteArrayInputStream(exi));
        EXISource src = new EXISource(f);
        src.setInputSource(is);

        TransformerFactory tf = XMLUtils.getTransformerFactory();
        Transformer t = tf.newTransformer();

        StringWriter w = new StringWriter();
        Result result = new StreamResult(w);
        t.transform(src, result);

        return w.toString();
    }
}
