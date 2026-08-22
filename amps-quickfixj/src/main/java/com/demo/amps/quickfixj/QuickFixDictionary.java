package com.demo.amps.quickfixj;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A QuickFIX/J data-dictionary XML (FIX fields, enumerated values, messages,
 * components, repeating groups) loaded into lookup tables.
 *
 * <p>Callers point this at FIX 4.2 or any other version the XML describes.
 * The converter never invents fields that were not on the wire.
 */
public final class QuickFixDictionary {

    private final Map<Integer, FieldDef> byTag;
    private final Map<String, FieldDef> byName;
    private final Map<String, GroupDef> groupsByCountName;
    private final Map<Integer, GroupDef> groupsByCountTag;
    private final Map<String, MessageDef> messagesByType;

    QuickFixDictionary(
            Map<Integer, FieldDef> byTag,
            Map<String, FieldDef> byName,
            Map<String, GroupDef> groupsByCountName,
            Map<Integer, GroupDef> groupsByCountTag,
            Map<String, MessageDef> messagesByType) {
        this.byTag = byTag;
        this.byName = byName;
        this.groupsByCountName = groupsByCountName;
        this.groupsByCountTag = groupsByCountTag;
        this.messagesByType = messagesByType;
    }

    public static QuickFixDictionary fromPath(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (InputStream in = Files.newInputStream(path)) {
            return fromInputStream(in);
        }
    }

    public static QuickFixDictionary fromInputStream(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        try {
            return parse(readDocument(in));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("failed to parse QuickFIX/J dictionary XML", e);
        }
    }

    public static QuickFixDictionary fromXml(String xml) {
        Objects.requireNonNull(xml, "xml");
        try {
            return parse(readDocument(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to parse QuickFIX/J dictionary XML", e);
        }
    }

    public Optional<FieldDef> fieldByTag(int tag) {
        return Optional.ofNullable(byTag.get(tag));
    }

    public Optional<FieldDef> fieldByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        FieldDef direct = byName.get(name);
        if (direct != null) {
            return Optional.of(direct);
        }
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    public Optional<GroupDef> groupByCountName(String name) {
        return Optional.ofNullable(groupsByCountName.get(name));
    }

    public Optional<GroupDef> groupByCountTag(int tag) {
        return Optional.ofNullable(groupsByCountTag.get(tag));
    }

    public Optional<MessageDef> messageByType(String msgType) {
        return Optional.ofNullable(messagesByType.get(msgType));
    }

    private static Document readDocument(InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(in);
    }

    private static QuickFixDictionary parse(Document document) {
        Element root = document.getDocumentElement();
        if (root == null || !"fix".equals(local(root.getTagName()))) {
            throw new IllegalArgumentException("QuickFIX/J dictionary root must be <fix>");
        }

        Map<Integer, FieldDef> byTag = new LinkedHashMap<>();
        Map<String, FieldDef> byName = new LinkedHashMap<>();
        Element fieldsEl = child(root, "fields");
        if (fieldsEl != null) {
            for (Element fieldEl : children(fieldsEl, "field")) {
                FieldDef def = FieldDef.fromXml(fieldEl);
                byTag.put(def.number(), def);
                byName.put(def.name(), def);
                byName.put(def.name().toLowerCase(Locale.ROOT), def);
            }
        }

        Map<String, Element> componentEls = new LinkedHashMap<>();
        Element componentsEl = child(root, "components");
        if (componentsEl != null) {
            for (Element componentEl : children(componentsEl, "component")) {
                String name = componentEl.getAttribute("name");
                if (!name.isEmpty()) {
                    componentEls.put(name, componentEl);
                }
            }
        }

        Map<String, GroupDef> groupsByCountName = new LinkedHashMap<>();
        Map<Integer, GroupDef> groupsByCountTag = new LinkedHashMap<>();
        Map<String, MessageDef> messagesByType = new LinkedHashMap<>();

        Element messagesEl = child(root, "messages");
        if (messagesEl != null) {
            for (Element messageEl : children(messagesEl, "message")) {
                String msgType = messageEl.getAttribute("msgtype");
                String msgName = messageEl.getAttribute("name");
                List<GroupDef> groups = new ArrayList<>();
                collectGroups(messageEl, byName, componentEls, groups);
                MessageDef message = new MessageDef(msgName, msgType, indexGroups(groups));
                if (!msgType.isEmpty()) {
                    messagesByType.put(msgType, message);
                }
                for (GroupDef group : flattenGroups(groups)) {
                    groupsByCountName.putIfAbsent(group.countName(), group);
                    groupsByCountTag.putIfAbsent(group.countTag(), group);
                }
            }
        }

        // Groups declared only inside components still need to be findable on
        // partial/delta payloads that have no MsgType.
        for (Element componentEl : componentEls.values()) {
            List<GroupDef> groups = new ArrayList<>();
            collectGroups(componentEl, byName, componentEls, groups);
            for (GroupDef group : flattenGroups(groups)) {
                groupsByCountName.putIfAbsent(group.countName(), group);
                groupsByCountTag.putIfAbsent(group.countTag(), group);
            }
        }

        return new QuickFixDictionary(
                Collections.unmodifiableMap(byTag),
                Collections.unmodifiableMap(byName),
                Collections.unmodifiableMap(groupsByCountName),
                Collections.unmodifiableMap(groupsByCountTag),
                Collections.unmodifiableMap(messagesByType));
    }

    private static void collectGroups(
            Element parent,
            Map<String, FieldDef> byName,
            Map<String, Element> componentEls,
            List<GroupDef> sink) {
        for (Element child : elementChildren(parent)) {
            String tag = local(child.getTagName());
            if ("group".equals(tag)) {
                sink.add(groupFromXml(child, byName, componentEls));
            } else if ("component".equals(tag)) {
                Element resolved = componentEls.get(child.getAttribute("name"));
                if (resolved != null) {
                    collectGroups(resolved, byName, componentEls, sink);
                }
            }
        }
    }

    private static GroupDef groupFromXml(
            Element groupEl, Map<String, FieldDef> byName, Map<String, Element> componentEls) {
        String countName = groupEl.getAttribute("name");
        FieldDef countField = byName.get(countName);
        if (countField == null) {
            throw new IllegalArgumentException("group " + countName + " has no <field> definition");
        }
        List<String> memberNames = new ArrayList<>();
        List<GroupDef> nested = new ArrayList<>();
        for (Element child : elementChildren(groupEl)) {
            String tag = local(child.getTagName());
            if ("field".equals(tag)) {
                memberNames.add(child.getAttribute("name"));
            } else if ("group".equals(tag)) {
                GroupDef nestedGroup = groupFromXml(child, byName, componentEls);
                nested.add(nestedGroup);
                memberNames.add(nestedGroup.countName());
            } else if ("component".equals(tag)) {
                Element resolved = componentEls.get(child.getAttribute("name"));
                if (resolved != null) {
                    collectMembers(resolved, byName, componentEls, memberNames, nested);
                }
            }
        }
        String delimiter = memberNames.isEmpty() ? countName : memberNames.get(0);
        return new GroupDef(countName, countField.number(), delimiter, memberNames, nested);
    }

    private static void collectMembers(
            Element parent,
            Map<String, FieldDef> byName,
            Map<String, Element> componentEls,
            List<String> memberNames,
            List<GroupDef> nested) {
        for (Element child : elementChildren(parent)) {
            String tag = local(child.getTagName());
            if ("field".equals(tag)) {
                memberNames.add(child.getAttribute("name"));
            } else if ("group".equals(tag)) {
                GroupDef nestedGroup = groupFromXml(child, byName, componentEls);
                nested.add(nestedGroup);
                memberNames.add(nestedGroup.countName());
            } else if ("component".equals(tag)) {
                Element resolved = componentEls.get(child.getAttribute("name"));
                if (resolved != null) {
                    collectMembers(resolved, byName, componentEls, memberNames, nested);
                }
            }
        }
    }

    private static Map<String, GroupDef> indexGroups(List<GroupDef> groups) {
        Map<String, GroupDef> index = new LinkedHashMap<>();
        for (GroupDef group : flattenGroups(groups)) {
            index.put(group.countName(), group);
        }
        return Collections.unmodifiableMap(index);
    }

    private static List<GroupDef> flattenGroups(List<GroupDef> groups) {
        List<GroupDef> all = new ArrayList<>();
        for (GroupDef group : groups) {
            all.add(group);
            all.addAll(flattenGroups(group.nested()));
        }
        return all;
    }

    private static Element child(Element parent, String name) {
        for (Element el : elementChildren(parent)) {
            if (name.equals(local(el.getTagName()))) {
                return el;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Element el : elementChildren(parent)) {
            if (name.equals(local(el.getTagName()))) {
                out.add(el);
            }
        }
        return out;
    }

    private static List<Element> elementChildren(Element parent) {
        NodeList nodes = parent.getChildNodes();
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) {
                out.add(element);
            }
        }
        return out;
    }

    private static String local(String tagName) {
        int colon = tagName.indexOf(':');
        return colon < 0 ? tagName : tagName.substring(colon + 1);
    }

    /** A FIX field as declared in the dictionary. */
    public static final class FieldDef {
        private final int number;
        private final String name;
        private final String type;
        private final Map<String, String> codeToMeaning;
        private final Map<String, String> meaningToCode;

        FieldDef(
                int number,
                String name,
                String type,
                Map<String, String> codeToMeaning,
                Map<String, String> meaningToCode) {
            this.number = number;
            this.name = name;
            this.type = type;
            this.codeToMeaning = codeToMeaning;
            this.meaningToCode = meaningToCode;
        }

        static FieldDef fromXml(Element fieldEl) {
            int number = Integer.parseInt(fieldEl.getAttribute("number"));
            String name = fieldEl.getAttribute("name");
            String type = fieldEl.getAttribute("type");
            Map<String, String> codeToMeaning = new LinkedHashMap<>();
            Map<String, String> meaningToCode = new LinkedHashMap<>();
            for (Element valueEl : children(fieldEl, "value")) {
                String code = valueEl.getAttribute("enum");
                String description = valueEl.getAttribute("description");
                if (code.isEmpty()) {
                    continue;
                }
                String meaning = toIdentifier(description, code);
                codeToMeaning.put(code, meaning);
                meaningToCode.put(meaning, code);
                meaningToCode.putIfAbsent(meaning.toLowerCase(Locale.ROOT), code);
                if (!description.isEmpty()) {
                    meaningToCode.putIfAbsent(description, code);
                    meaningToCode.putIfAbsent(description.toLowerCase(Locale.ROOT), code);
                }
            }
            return new FieldDef(
                    number,
                    name,
                    type,
                    Collections.unmodifiableMap(codeToMeaning),
                    Collections.unmodifiableMap(meaningToCode));
        }

        public int number() {
            return number;
        }

        public String name() {
            return name;
        }

        public String type() {
            return type;
        }

        public boolean isEnumerated() {
            return !codeToMeaning.isEmpty();
        }

        public Optional<String> meaningOf(String code) {
            return Optional.ofNullable(codeToMeaning.get(code));
        }

        public Optional<String> codeOf(String meaningOrCode) {
            if (meaningOrCode == null) {
                return Optional.empty();
            }
            if (codeToMeaning.containsKey(meaningOrCode)) {
                return Optional.of(meaningOrCode);
            }
            String mapped = meaningToCode.get(meaningOrCode);
            if (mapped != null) {
                return Optional.of(mapped);
            }
            return Optional.ofNullable(meaningToCode.get(meaningOrCode.toLowerCase(Locale.ROOT)));
        }
    }

    /** A repeating group keyed by its count field (e.g. {@code NoAllocs} / 78). */
    public static final class GroupDef {
        private final String countName;
        private final int countTag;
        private final String delimiterName;
        private final List<String> memberNames;
        private final List<GroupDef> nested;
        private final Map<String, GroupDef> nestedByCountName;

        GroupDef(
                String countName,
                int countTag,
                String delimiterName,
                List<String> memberNames,
                List<GroupDef> nested) {
            this.countName = countName;
            this.countTag = countTag;
            this.delimiterName = delimiterName;
            this.memberNames = List.copyOf(memberNames);
            this.nested = List.copyOf(nested);
            Map<String, GroupDef> index = new LinkedHashMap<>();
            for (GroupDef group : nested) {
                index.put(group.countName(), group);
            }
            this.nestedByCountName = Collections.unmodifiableMap(index);
        }

        public String countName() {
            return countName;
        }

        public int countTag() {
            return countTag;
        }

        public String delimiterName() {
            return delimiterName;
        }

        public List<String> memberNames() {
            return memberNames;
        }

        public List<GroupDef> nested() {
            return nested;
        }

        boolean containsMember(String fieldName) {
            return memberNames.contains(fieldName) || nestedByCountName.containsKey(fieldName);
        }

        Optional<GroupDef> nestedGroup(String countName) {
            return Optional.ofNullable(nestedByCountName.get(countName));
        }
    }

    /** A FIX message definition (used to prefer that message's group layout). */
    public static final class MessageDef {
        private final String name;
        private final String msgType;
        private final Map<String, GroupDef> groupsByCountName;

        MessageDef(String name, String msgType, Map<String, GroupDef> groupsByCountName) {
            this.name = name;
            this.msgType = msgType;
            this.groupsByCountName = groupsByCountName;
        }

        public String name() {
            return name;
        }

        public String msgType() {
            return msgType;
        }

        Optional<GroupDef> group(String countName) {
            return Optional.ofNullable(groupsByCountName.get(countName));
        }
    }

    /**
     * QuickFIX/J {@code description} attributes become NVFIX enum tokens:
     * {@code "Partially filled"} → {@code PartiallyFilled}.
     */
    static String toIdentifier(String description, String fallback) {
        if (description == null || description.isBlank()) {
            return fallback;
        }
        StringBuilder out = new StringBuilder();
        boolean capNext = true;
        for (int i = 0; i < description.length(); i++) {
            char c = description.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(capNext ? Character.toUpperCase(c) : c);
                capNext = false;
            } else {
                capNext = true;
            }
        }
        return out.isEmpty() ? fallback : out.toString();
    }
}
