package com.teamcomplex.plasticinsight.core

import java.io.ByteArrayInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLResolver
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/** Small forward-only XML cursor with external resource access disabled. */
internal class PlasticXmlCursor private constructor(
    private val reader: XMLStreamReader,
    private val cancellationCheck: () -> Unit,
) : AutoCloseable {
    val elementName: String
        get() {
            requireUnqualifiedElement()
            return reader.localName
        }

    fun moveToRoot(expectedName: String) {
        while (reader.hasNext()) {
            when (next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    if (elementName != expectedName) {
                        throw PlasticParseException("Plastic XML has an unexpected root element.")
                    }
                    return
                }

                XMLStreamConstants.CHARACTERS,
                XMLStreamConstants.CDATA,
                XMLStreamConstants.SPACE,
                -> requireWhitespace()

                XMLStreamConstants.COMMENT,
                XMLStreamConstants.PROCESSING_INSTRUCTION,
                XMLStreamConstants.START_DOCUMENT,
                -> Unit

                else -> throw PlasticParseException("Plastic XML has no root element.")
            }
        }
        throw PlasticParseException("Plastic XML has no root element.")
    }

    fun readChildren(
        parentName: String,
        onChild: (String) -> Unit,
    ) {
        while (reader.hasNext()) {
            when (next()) {
                XMLStreamConstants.START_ELEMENT -> onChild(elementName)
                XMLStreamConstants.END_ELEMENT -> {
                    if (elementName != parentName) {
                        throw PlasticParseException("Plastic XML closed an unexpected element.")
                    }
                    return
                }

                XMLStreamConstants.CHARACTERS,
                XMLStreamConstants.CDATA,
                XMLStreamConstants.SPACE,
                -> requireWhitespace()

                XMLStreamConstants.COMMENT,
                XMLStreamConstants.PROCESSING_INSTRUCTION,
                -> Unit

                else -> throw PlasticParseException("Plastic XML contains invalid element content.")
            }
        }
        throw PlasticParseException("Plastic XML ended before closing an element.")
    }

    fun readText(elementName: String): String {
        val result = StringBuilder()
        while (reader.hasNext()) {
            when (next()) {
                XMLStreamConstants.CHARACTERS,
                XMLStreamConstants.CDATA,
                XMLStreamConstants.SPACE,
                XMLStreamConstants.ENTITY_REFERENCE,
                -> result.append(reader.text)

                XMLStreamConstants.COMMENT,
                XMLStreamConstants.PROCESSING_INSTRUCTION,
                -> Unit

                XMLStreamConstants.END_ELEMENT -> {
                    if (this.elementName != elementName) {
                        throw PlasticParseException("Plastic XML closed an unexpected text element.")
                    }
                    return result.toString()
                }

                XMLStreamConstants.START_ELEMENT ->
                    throw PlasticParseException("Plastic XML contains nested markup in a text field.")

                else -> throw PlasticParseException("Plastic XML contains invalid text content.")
            }
        }
        throw PlasticParseException("Plastic XML ended inside a text field.")
    }

    fun skipElement() {
        var depth = 1
        while (reader.hasNext()) {
            when (next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    elementName
                    depth++
                }

                XMLStreamConstants.END_ELEMENT -> {
                    elementName
                    depth--
                    if (depth == 0) {
                        return
                    }
                }
            }
        }
        throw PlasticParseException("Plastic XML ended inside an unknown element.")
    }

    fun requireDocumentEnd() {
        while (reader.hasNext()) {
            when (next()) {
                XMLStreamConstants.END_DOCUMENT -> return
                XMLStreamConstants.CHARACTERS,
                XMLStreamConstants.CDATA,
                XMLStreamConstants.SPACE,
                -> requireWhitespace()

                XMLStreamConstants.COMMENT,
                XMLStreamConstants.PROCESSING_INSTRUCTION,
                -> Unit

                else -> throw PlasticParseException("Plastic XML contains content after its root element.")
            }
        }
    }

    override fun close() {
        try {
            reader.close()
        } catch (_: XMLStreamException) {
            // Parsing already consumed the bounded in-memory stream.
        }
    }

    private fun next(): Int {
        cancellationCheck()
        val event = try {
            reader.next()
        } catch (exception: XMLStreamException) {
            throw PlasticParseException("Plastic returned malformed XML.", exception)
        }
        if (event == XMLStreamConstants.DTD) {
            throw PlasticParseException("Plastic XML must not contain a document type declaration.")
        }
        return event
    }

    private fun requireWhitespace() {
        if (!reader.text.isNullOrBlank()) {
            throw PlasticParseException("Plastic XML contains unexpected character data.")
        }
    }

    private fun requireUnqualifiedElement() {
        if (!reader.namespaceURI.isNullOrEmpty() || !reader.prefix.isNullOrEmpty()) {
            throw PlasticParseException("Plastic XML contains an unexpected namespace.")
        }
    }

    companion object {
        fun open(
            output: ByteArray,
            cancellationCheck: () -> Unit,
        ): PlasticXmlCursor {
            if (output.isEmpty()) {
                throw PlasticParseException("Plastic returned empty XML output.")
            }

            val factory = XMLInputFactory.newFactory().apply {
                setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
                setProperty(XMLInputFactory.IS_COALESCING, true)
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty("javax.xml.stream.isSupportingExternalEntities", false)
                setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true)
                xmlResolver = XMLResolver { _, _, _, _ ->
                    throw XMLStreamException("External XML entities are disabled.")
                }
            }

            val reader = try {
                factory.createXMLStreamReader(ByteArrayInputStream(output))
            } catch (exception: XMLStreamException) {
                throw PlasticParseException("Plastic returned malformed XML.", exception)
            }
            return PlasticXmlCursor(reader, cancellationCheck)
        }
    }
}
