// SPDX-License-Identifier: Apache-2.0

package it.unisannio.bomgenerator.builders.buildersV3.AI.MLflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Parser of text complaint with AIBomMaker description format
public class DescriptionParser {

    static Logger logger = LoggerFactory.getLogger(DescriptionParser.class.getName());

    public static Dictionary<String, List<String>> parseText(String text) {

        if (text == null || text.isBlank()) {
            logger.info("Empty text provided to DescriptionParser, returning empty dictionary.");
            return null;
        }

        if (!text.contains("~spdxStart~") || !text.contains("~spdxEnd~")) {
            logger.info("Description does not contain an AIGen SPDX metadata section.");
            return null;
        }

        // Extract only the text in the SPDX fields section.
        String[] sections = text.split("~spdxEnd~");

        if (sections.length > 2) {
            logger.error("Invalid text format, only one ~spdxEnd~ expected, found " + (sections.length - 1));
            return null;
        }

        String newText = sections[0];
        String[] startSections = newText.split("~spdxStart~", 2);
        if (startSections.length < 2) {
            logger.info("Description does not contain a valid AIGen SPDX metadata start marker.");
            return null;
        }
        newText = startSections[1];

        String[] lines = newText.split("~spdxF~");
        Dictionary<String, List<String>> dict = new Hashtable<String, List<String>>();

        for (String string : lines) {

            if (string.equals(""))
                continue;

            String[] keyAndValues = string.split(":", 2);

            if (keyAndValues.length < 2)
                continue;

            List<String> values = new ArrayList<String>(Arrays.asList(keyAndValues[1].split("~")));

            if (keyAndValues[0] != null && !values.isEmpty()) {
                if (values.get(0).isBlank()) {
                    values.remove(0);
                }
                dict.put(keyAndValues[0].trim(), values);
            }
        }

        return dict;
    }

}
