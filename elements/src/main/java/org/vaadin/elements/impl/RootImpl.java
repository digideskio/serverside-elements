package org.vaadin.elements.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.vaadin.elements.ElementIntegration;
import org.vaadin.elements.Elements;
import org.vaadin.elements.Import;
import org.vaadin.elements.Node;
import org.vaadin.elements.Root;
import org.vaadin.elements.TextNode;

import com.vaadin.server.EncodeResult;
import com.vaadin.server.JsonCodec;
import com.vaadin.ui.Component;
import com.vaadin.ui.JavaScriptFunction;

import elemental.json.Json;
import elemental.json.JsonArray;
import elemental.json.JsonObject;
import elemental.json.JsonType;
import elemental.json.JsonValue;

public class RootImpl extends ElementImpl implements Root {
    private ElementIntegration owner;

    private int callbackIdSequence = 0;
    private int nodeIdSequence = 0;
    private int fetchCallbackSequence = 0;

    private final Map<NodeImpl, Integer> nodeToId = new HashMap<>();
    private final Map<Integer, NodeImpl> idToNode = new HashMap<>();

    private JsonArray pendingCommands = Json.createArray();

    private final Map<Integer, Runnable> fetchDomCallbacks = new HashMap<>();
    private final Map<Integer, Component[]> fetchDomComponents = new HashMap<>();

    private final Set<String> handledImports = new HashSet<>();

    public RootImpl(ElementIntegration owner) {
        super(new org.jsoup.nodes.Element(org.jsoup.parser.Tag.valueOf("div"),
                ""));

        Context context = new Context() {
            @Override
            protected void adopt(NodeImpl node) {
                super.adopt(node);

                if (node != RootImpl.this) {
                    adoptNode(node);
                }
            }

            @Override
            protected void remove(NodeImpl node) {
                addCommand("remove", node);
                Integer id = nodeToId.remove(node);
                idToNode.remove(id);

                super.remove(node);
            }

            @Override
            public RootImpl getRoot() {
                return RootImpl.this;
            }
        };
        this.owner = owner;

        context.adopt(this);

        Integer ownId = Integer.valueOf(0);
        nodeToId.put(this, ownId);
        idToNode.put(ownId, this);
    }

    private void addCommand(String name, Node target, JsonValue... params) {
        assert target == null || target.getRoot() == this;

        JsonArray c = Json.createArray();
        c.set(0, name);

        if (target != null) {
            c.set(1, nodeToId.get(target).doubleValue());
        }

        Arrays.asList(params).forEach(p -> c.set(c.length(), p));

        pendingCommands.set(pendingCommands.length(), c);

        owner.markAsDirty();
    }

    private void adoptNode(NodeImpl child) {
        // Even numbers generated by server, odd by client
        nodeIdSequence += 2;
        Integer id = Integer.valueOf(nodeIdSequence);
        adoptNode(child, id);
    }

    private void adoptNode(NodeImpl child, Integer id) {
        nodeToId.put(child, id);
        idToNode.put(id, child);

        resolveImports(child.getClass());

        // Enqueue initialization operations
        if (child instanceof ElementImpl) {
            ElementImpl e = (ElementImpl) child;

            addCommand("createElement", child, Json.create(e.getTag()));

            e.getAttributeNames().forEach(name -> setAttributeChange(e, name));
            e.flushCommandQueues();
        } else if (child instanceof TextNodeImpl) {
            TextNode t = (TextNode) child;

            addCommand("createText", child, Json.create(t.getText()));
        } else {
            throw new RuntimeException("Unsupported node type: "
                    + child.getClass());
        }

        // Finally add append command
        addCommand("appendChild", child.getParent(),
                Json.create(id.doubleValue()));
    }

    private void resolveImports(Class<? extends NodeImpl> type) {
        Arrays.stream(type.getInterfaces())
                .map(i -> i.getAnnotation(Import.class))
                .filter(Objects::nonNull).map(Import::value)
                .forEach(this::importHtml);
    }

    @Override
    public void importHtml(String url) {
        if (!handledImports.contains(url)) {
            handledImports.add(url);
            addCommand("import", null, Json.create(url));
        }
    }

    void setAttributeChange(ElementImpl element, String name) {
        String value = element.getAttribute(name);
        if (value == null) {
            addCommand("removeAttribute", element, Json.create(name));
        } else {
            addCommand("setAttribute", element, Json.create(name),
                    Json.create(value));
        }
    }

    public void setTextChange(TextNodeImpl textNode, String text) {
        addCommand("setText", textNode, Json.create(text));
    }

    public JsonArray flushPendingCommands() {
        for (Entry<Integer, Component[]> entry : fetchDomComponents.entrySet()) {
            JsonArray connectorsJson = Json.createArray();
            for (Component component : entry.getValue()) {
                connectorsJson.set(connectorsJson.length(),
                        component.getConnectorId());
            }

            addCommand("fetchDom", null,
                    Json.create(entry.getKey().intValue()), connectorsJson);
        }
        fetchDomComponents.clear();

        JsonArray payload = pendingCommands;
        pendingCommands = Json.createArray();
        return payload;
    }

    void eval(ElementImpl element, String script, Object[] arguments) {
        // Param values
        JsonArray params = Json.createArray();

        // Array of param indices that should be treated as callbacks
        JsonArray callbacks = Json.createArray();

        for (int i = 0; i < arguments.length; i++) {
            Object value = arguments[0];
            Class<? extends Object> type = value.getClass();

            if (JavaScriptFunction.class.isAssignableFrom(type)) {
                // TODO keep sequence per element instead of "global"
                int cid = callbackIdSequence++;
                element.setCallback(cid, (JavaScriptFunction) value);

                value = Integer.valueOf(cid);
                type = Integer.class;

                callbacks.set(callbacks.length(), i);
            }

            EncodeResult encodeResult = JsonCodec.encode(value, null, type,
                    null);
            params.set(i, encodeResult.getEncodedValue());
        }

        addCommand("eval", element, Json.create(script), params, callbacks);
    }

    public void handleCallback(JsonArray arguments) {
        JsonArray attributeChanges = arguments.getArray(1);
        for (int i = 0; i < attributeChanges.length(); i++) {
            JsonArray attributeChange = attributeChanges.getArray(i);
            int id = (int) attributeChange.getNumber(0);
            String attribute = attributeChange.getString(1);
            JsonValue value = attributeChange.get(2);

            NodeImpl target = idToNode.get(Integer.valueOf(id));
            if (value.getType() == JsonType.NULL) {
                target.node.removeAttr(attribute);
            } else {
                target.node.attr(attribute, value.asString());
            }
        }

        JsonArray callbacks = arguments.getArray(0);
        for (int i = 0; i < callbacks.length(); i++) {
            JsonArray call = callbacks.getArray(i);

            int elementId = (int) call.getNumber(0);
            int cid = (int) call.getNumber(1);
            JsonArray params = call.getArray(2);

            ElementImpl element = (ElementImpl) idToNode.get(Integer
                    .valueOf(elementId));
            if (element == null) {
                System.out.println(cid + " detached?");
                return;
            }

            JavaScriptFunction callback = element.getCallback(cid);
            callback.call(params);
        }
    }

    @Override
    public String asHtml() {
        StringBuilder b = new StringBuilder();

        b.append(super.asHtml());

        return b.toString();
    }

    public void synchronize(int id, JsonArray hierarchy) {
        synchronizeRecursively(hierarchy, this);

        // Detach all removed nodes
        List<NodeImpl> detached = new ArrayList<>();
        for (NodeImpl node : new ArrayList<>(idToNode.values())) {
            NodeImpl parent = node;
            while (parent != this) {
                if (parent == null) {
                    detached.add(node);
                    break;
                }
                parent = (NodeImpl) parent.getParent();
            }
        }

        Context removedContext = new Context();
        detached.forEach(node -> removedContext.adopt(node));

        // Don't send the adopted structure to the client
        pendingCommands = Json.createArray();

        Runnable callback = fetchDomCallbacks.remove(Integer.valueOf(id));
        if (callback != null) {
            callback.run();
        }
    }

    public void init(String html) {
        // Clear state
        removeAllChildren();
        getAttributeNames().forEach(this::removeAttribute);

        nodeIdSequence = 2;

        Document bodyFragment = Jsoup.parseBodyFragment(html);
        List<org.jsoup.nodes.Node> childNodes = bodyFragment.body()
                .childNodes();
        assert childNodes.size() == 1;

        org.jsoup.nodes.Node rootNode = childNodes.get(0);

        while (rootNode.childNodeSize() != 0) {
            org.jsoup.nodes.Node child = rootNode.childNode(0);
            ((org.jsoup.nodes.Element) this.node).appendChild(child);
        }

        context.wrapChildren(this);

        for (Attribute a : rootNode.attributes()) {
            setAttribute(a.getKey(), a.getValue());
        }
        // Don't send the adopted structure to the client
        pendingCommands = Json.createArray();

        // TODO sync ids

        fetchDomCallbacks.values().forEach(Runnable::run);
        fetchDomCallbacks.clear();
    }

    private void synchronizeRecursively(JsonArray hierarchy, ElementImpl element) {
        int firstChild;
        JsonValue maybeAttributes = hierarchy.get(2);
        if (maybeAttributes.getType() == JsonType.OBJECT) {
            firstChild = 3;
            JsonObject attributes = (JsonObject) maybeAttributes;
            String[] names = attributes.keys();

            HashSet<String> oldAttributes = new HashSet<>(
                    element.getAttributeNames());
            oldAttributes.removeAll(Arrays.asList(names));
            oldAttributes.forEach(n -> element.removeAttribute(n));

            Arrays.stream(names).forEach(
                    name -> element.setAttribute(name,
                            attributes.getString(name)));
        } else {
            firstChild = 2;
        }

        ArrayList<NodeImpl> newChildren = new ArrayList<>();
        for (int i = firstChild; i < hierarchy.length(); i++) {
            JsonArray child = hierarchy.getArray(i);
            int nodeId;
            NodeImpl childNode;

            switch (child.get(0).getType()) {
            case NUMBER:
                TextNodeImpl textNode;
                nodeId = (int) child.getNumber(0);
                String text = child.getString(1);

                if (nodeId % 2 == 0) {
                    // old node
                    textNode = (TextNodeImpl) idToNode.get(Integer
                            .valueOf(nodeId));
                    textNode.setText(text);
                } else {
                    // new node
                    textNode = (TextNodeImpl) Elements.createText(text);
                }

                childNode = textNode;
                break;
            case STRING:
                // Element node
                ElementImpl childElement;
                String tag = child.getString(0);
                nodeId = (int) child.getNumber(1);

                if (nodeId % 2 == 0) {
                    // old node
                    childElement = (ElementImpl) idToNode.get(Integer
                            .valueOf(nodeId));
                    assert childElement.getTag().equals(tag);
                } else {
                    // new node
                    childElement = (ElementImpl) Elements.create(tag);
                }

                synchronizeRecursively(child, childElement);

                childNode = childElement;
                break;
            default:
                throw new RuntimeException("Unsupported child JSON: "
                        + child.toJson());
            }

            if (nodeId % 2 == 1) {
                context.adopt(childNode);
                adoptNode(childNode, nodeId);
            }

            newChildren.add(childNode);
        }

        element.resetChildren(newChildren);
    }

    @Override
    public void fetchDom(Runnable callback, Component... connectorsToInlcude) {
        assert callback != null;

        Integer id = Integer.valueOf(fetchCallbackSequence++);
        fetchDomCallbacks.put(id, callback);
        fetchDomComponents.put(id, connectorsToInlcude);

        owner.markAsDirty();
    }

    void setAttributeBound(ElementImpl elementImpl, String attributeName,
            String eventName) {
        addCommand("bindAttribute", elementImpl, Json.create(attributeName),
                Json.create(eventName));
    }

}
