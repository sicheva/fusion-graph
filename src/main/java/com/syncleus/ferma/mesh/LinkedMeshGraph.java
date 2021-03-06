package com.syncleus.ferma.mesh;

import com.syncleus.ferma.ClassInitializer;
import com.syncleus.ferma.FramedTransactionalGraph;
import com.tinkerpop.blueprints.*;

import java.util.*;

public class LinkedMeshGraph implements MeshGraph {
    private final FramedTransactionalGraph metagraph;
    private final Set<TransactionalGraph> pendingTransactions = new HashSet<>();
    private final Set<Object> unreadableSubgraphIds = new HashSet<>();
    private final TreeGraphCache<Object, TransactionalGraph> cachedSubgraphs;
    private Object writeSubgraphId = null;

    public LinkedMeshGraph(final FramedTransactionalGraph metagraph) {
        if (metagraph == null)
            throw new IllegalArgumentException("metagraph can not be null");

        this.metagraph = metagraph;
        this.cachedSubgraphs = new MeshGraphCache();
    }

    public LinkedMeshGraph(final FramedTransactionalGraph metagraph, final int maxConnections) {
        if (metagraph == null)
            throw new IllegalArgumentException("metagraph can not be null");
        if (maxConnections < 2)
            throw new IllegalArgumentException("maxConnections must be 2 or greater");

        this.metagraph = metagraph;
        this.cachedSubgraphs = new MeshGraphCache(maxConnections);
    }

    @Override
    public <G extends SubgraphVertex> Object addSubgraph(Class<G> subgraphType) {
        this.pendingTransactions.add(this.getRawGraph());
        final G subgraphVertex = this.getRawGraph().addFramedVertex(subgraphType);
        return subgraphVertex.getId();
    }

    @Override
    public <G extends SubgraphVertex> Object addSubgraph(ClassInitializer<G> subgraphInitializer) {
        this.pendingTransactions.add(this.getRawGraph());
        final G subgraphVertex = this.getRawGraph().addFramedVertex(subgraphInitializer);
        return subgraphVertex.getId();
    }

    @Override
    public void removeSubgraph(final Object subgraphName) {
        this.pendingTransactions.add(this.getRawGraph());
        //subgraphName should be unique, so this should only get called once, the loop is incase transactions aren't locking
        //as expected and as such the name was set multiple times.
        this.getRawGraph().getVertex(subgraphName).remove();
    }

    @Override
    public Iterator<?> iterateSubgraphIds() {
        this.pendingTransactions.add(this.getRawGraph());
        return new Iterator<Object>() {
            final Iterator<? extends SubgraphVertex> vertexIterator = getRawGraph().getFramedVertices(SubgraphVertex.class).iterator();

            @Override
            public boolean hasNext() {
                return this.vertexIterator.hasNext();
            }

            @Override
            public String next() {
                return this.vertexIterator.next().getId();
            }

            @Override
            public void remove() {
                this.vertexIterator.remove();
            }
        };
    }

    @Override
    public FramedTransactionalGraph getRawGraph() {
        return this.metagraph;
    }

    @Override
    public Features getFeatures() {
        return this.featureMerger();
    }

    @Override
    public boolean addReadSubgraph(Object subgraphId) {
        return this.unreadableSubgraphIds.remove(subgraphId);
    }

    @Override
    public boolean removeReadSubgraph(Object subgraphId) {
        if (this.writeSubgraphId.equals(subgraphId))
            throw new IllegalArgumentException("the write subgraph can not be removed from the list of readable subgraphs");

        this.pendingTransactions.add(this.getRawGraph());

        if (!this.isSubgraphIdUsed(subgraphId))
            throw new IllegalArgumentException("subgraphId does not exist");

        return this.unreadableSubgraphIds.add(subgraphId);
    }

    @Override
    public boolean isReadSubgraph(Object subgraphId) {
        this.pendingTransactions.add(this.getRawGraph());
        if (!this.isSubgraphIdUsed(subgraphId))
            throw new IllegalArgumentException("subgraphId does not exist");

        return (!this.unreadableSubgraphIds.contains(subgraphId));
    }

    @Override
    public Iterator<Object> iterateReadSubgraphIds() {
        this.pendingTransactions.add(this.getRawGraph());
        return new Iterator<Object>() {
            Iterator<? extends SubgraphVertex> vertexIterator = null;
            Object queuedSubgraphId = null;

            private void initializeIterator() {
                this.vertexIterator = getRawGraph().getFramedVertices(SubgraphVertex.class).iterator();
                this.advanceQueue();
            }

            private void advanceQueue() {
                while (this.vertexIterator.hasNext()) {
                    final Object nextSubgraphId = this.vertexIterator.next().getId();
                    if (!unreadableSubgraphIds.contains(nextSubgraphId)) {
                        this.queuedSubgraphId = nextSubgraphId;
                        return;
                    }
                }
                this.queuedSubgraphId = null;
            }

            @Override
            public boolean hasNext() {
                if (this.vertexIterator == null)
                    this.initializeIterator();

                return (this.queuedSubgraphId != null);
            }

            @Override
            public Object next() {
                if (this.vertexIterator == null)
                    this.initializeIterator();

                //if queuedSubgraph is null then we should throw an exception, we do this by calling next on the vertexIterator
                //this ensures the type and message of the exception is preserved.
                if (this.queuedSubgraphId == null) {
                    this.vertexIterator.next();
                    //this should never be reached
                    assert false;
                }

                final Object nextId = this.queuedSubgraphId;
                this.advanceQueue();
                return nextId;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("Can not remove read subgraphs from the iterator directly");
            }
        };
    }

    @Override
    public void setWriteSubgraph(Object subgraphId) {
        this.pendingTransactions.add(this.getRawGraph());

        if (!this.isSubgraphIdUsed(subgraphId))
            throw new IllegalArgumentException("subgraphId does not exist");

        this.unreadableSubgraphIds.remove(subgraphId);
        this.writeSubgraphId = subgraphId;
    }

    @Override
    public Object getWriteSubgraphId() {
        this.pendingTransactions.add(this.getRawGraph());

        if (!this.isSubgraphIdUsed(this.writeSubgraphId))
            this.writeSubgraphId = null;

        return this.writeSubgraphId;
    }

    @Override
    public void moveVertex(Vertex vertex, Object subgraphId) {
        // TODO : Implement this
        throw new UnsupportedOperationException("This method hasnt been implemented yet, comming soon!");
    }

    @Override
    public Vertex addVertex(Object id) {
        if ((id != null) && !(id instanceof MeshId))
            throw new IllegalArgumentException("id must either be null or a MeshId");

        //this is safe even if id is null
        final MeshId meshId = (MeshId) id;
        Object subgraphVertexId;
        Object writeGraphId;
        if (meshId == null) {
            if (this.writeSubgraphId == null)
                throw new IllegalStateException("No default wriatable graph is specified, either pass in a MeshId or set a writable graph.");

            subgraphVertexId = null;
            writeGraphId = this.writeSubgraphId;
        } else {
            subgraphVertexId = meshId.getSubgraphVertexId();
            writeGraphId = meshId.getSubgraphId();
        }

        final TransactionalGraph writeGraph = this.cachedSubgraphs.get(writeGraphId);

        this.pendingTransactions.add(writeGraph);
        return new NestedVertex(writeGraph.addVertex(subgraphVertexId), writeGraphId);
    }

    @Override
    public Vertex getVertex(Object id) {
        if (!(id instanceof MeshId))
            return null;

        final MeshId meshId = (MeshId) id;
        final TransactionalGraph readGraph = this.cachedSubgraphs.get(meshId.getSubgraphId());
        this.pendingTransactions.add(readGraph);
        return new NestedVertex(readGraph.getVertex(meshId.getSubgraphVertexId()), meshId.getSubgraphId());
    }

    @Override
    public void removeVertex(Vertex vertex) {
        if(!(vertex instanceof NestedVertex))
            throw new IllegalArgumentException("vertex is not a member of any subgraphs");

        final NestedVertex nestedVertex = (NestedVertex) vertex;

        final TransactionalGraph targetGraph = cachedSubgraphs.get(nestedVertex.getParentId());

        //remove the vertex from the subgraph
        pendingTransactions.add(targetGraph);
        nestedVertex.getDelegate().remove();

        //remove any mesh edges which link to this vertex from outside the subgraph
        final Object subgraphVertexId = nestedVertex.getId().getSubgraphVertexId();
        pendingTransactions.add(metagraph);
        metagraph.v(nestedVertex.getParentId()).inE("link").has("outId", subgraphVertexId).removeAll();
        metagraph.v(nestedVertex.getParentId()).outE("link").has("inId", subgraphVertexId).removeAll();
    }

    @Override
    public Iterable<Vertex> getVertices() {
        return new Iterable<Vertex>() {
            @Override
            public Iterator<Vertex> iterator() {
                //we collect all the iterators at the begining to ensure they remain consistent.
                final Set<NestedVertexIterator> subvertexIterators = new HashSet<>();
                for (final Vertex subgraph : metagraph.getVertices()) {
                    final Object subgraphId = subgraph.getId();
                    final Iterator<Vertex> subvertexIterable = cachedSubgraphs.get(subgraphId).getVertices().iterator();
                    subvertexIterators.add(new NestedVertexIterator(subvertexIterable, subgraphId));
                }

                return new SubgraphVertexIterator(subvertexIterators);
            }
        };
    }

    @Override
    public Iterable<Vertex> getVertices(final String key, final Object value) {
        return new Iterable<Vertex>() {
            @Override
            public Iterator<Vertex> iterator() {
                //we collect all the iterators at the begining to ensure they remain consistent.
                final Set<NestedVertexIterator> subvertexIterators = new HashSet<>();
                for (final Vertex subgraph : metagraph.getVertices()) {
                    final Object subgraphId = subgraph.getId();
                    final Iterator<Vertex> subvertexIterable = cachedSubgraphs.get(subgraphId).getVertices(key, value).iterator();
                    subvertexIterators.add(new NestedVertexIterator(subvertexIterable, subgraphId));
                }

                return new SubgraphVertexIterator(subvertexIterators);
            }
        };
    }

    @Override
    public Edge addEdge(final Object id, final Vertex outVertex, final Vertex inVertex, final String label) {
        if ((id != null) && !(id instanceof MeshId))
            throw new IllegalArgumentException("id must either be null or a MeshId");
        if (!(outVertex instanceof NestedVertex))
            throw new IllegalArgumentException("outVertex is not a vertex from any of the subgraphs");
        if (!(inVertex instanceof NestedVertex))
            throw new IllegalArgumentException("inVertex is not a vertex from any of the subgraphs");

        final MeshId meshId = (MeshId) id;
        final NestedVertex nestedOutVertex = (NestedVertex) outVertex;
        final NestedVertex nestedInVertex = (NestedVertex) inVertex;
        final Object subgraphId;
        final Object subgraphEdgeId;
        if (meshId == null)
        {
            subgraphId = null;
            subgraphEdgeId = null;
        }
        else {
            subgraphId = meshId.getSubgraphId();
            subgraphEdgeId = meshId.getSubgraphVertexId();
        }

        //if both vertexes belong to the same subgraph than it is easy, just create a edge in that subgraph
        if(nestedOutVertex.getId().getSubgraphId().equals(nestedInVertex.getId().getSubgraphId())) {
            final Object writeGraphId = nestedOutVertex.getId().getSubgraphId();
            if( subgraphId != null && !subgraphId.equals(writeGraphId))
                throw new IllegalArgumentException("meshId was not null but the subgraphId did not match that of the vertex arguments");
            final TransactionalGraph subgraph = cachedSubgraphs.get(writeGraphId);
            this.pendingTransactions.add(subgraph);
            return new NestedEdge(subgraph.addEdge(subgraphEdgeId, nestedOutVertex.getDelegate(), nestedInVertex.getDelegate(), label), writeGraphId);
        }
        //we need to create a cross-graph edge
        else {
            if( subgraphId != null )
                throw new IllegalArgumentException("vertexs span subgraphs but id has a non null subgraphId");
            final Object inGraphId = nestedInVertex.getId().getSubgraphId();
            final Object outGraphId = nestedOutVertex.getId().getSubgraphId();

            final Vertex inGraphVertex = metagraph.getVertex(inGraphId);
            final Vertex outGraphVertex = metagraph.getVertex(outGraphId);
            final Edge subedge = metagraph.addEdge(subgraphEdgeId, outGraphVertex, inGraphVertex, "link");
            subedge.setProperty("sublabel", label);

            // TODO : Finish implementing this, we still need to cast this into a LinkedEdge!
            throw new UnsupportedOperationException("This method hasnt been implemented yet, comming soon!");
        }
    }

    @Override
    public Edge getEdge(Object id) {
        // TODO : Implement this
        throw new UnsupportedOperationException("This method hasnt been implemented yet, comming soon!");
    }

    @Override
    public void removeEdge(Edge edge) {
        // TODO : Implement this
        throw new UnsupportedOperationException("This method hasnt been implemented yet, comming soon!");
    }

    @Override
    public Iterable<Edge> getEdges() {
        // TODO : Implement this
        throw new UnsupportedOperationException("This method hasnt been implemented yet, comming soon!");
    }

    @Override
    public Iterable<Edge> getEdges(String key, Object value) {
        // TODO : Implement this
        throw new UnsupportedOperationException("This method hasnt been implemented yet, comming soon!");
    }

    @Override
    public GraphQuery query() {
        // TODO : Implement this
        throw new UnsupportedOperationException("This method hasnt been implemented yet, comming soon!");
    }

    @Override
    public void shutdown() {
        // TODO : Implement this
        throw new UnsupportedOperationException("This method hasnt been implemented yet, comming soon!");
    }

    @Override
    public void stopTransaction(Conclusion conclusion) {
        for (TransactionalGraph pendingTransaction : this.pendingTransactions)
            pendingTransaction.stopTransaction(conclusion);
        this.pendingTransactions.clear();
    }

    @Override
    public void commit() {
        //TODO : What happens when some commits succeeded and then one of the last ones throws an exception?
        for (TransactionalGraph pendingTransaction : this.pendingTransactions)
            pendingTransaction.commit();
        this.pendingTransactions.clear();
    }

    @Override
    public void rollback() {
        for (TransactionalGraph pendingTransaction : this.pendingTransactions)
            pendingTransaction.rollback();
        this.pendingTransactions.clear();
    }

    @Override
    public void resync() {
        // TODO : Implement this
        throw new UnsupportedOperationException("This method hasnt been implemented yet, comming soon!");
    }

    @Override
    public void clearConnectionCache() {
        this.cachedSubgraphs.clear();
    }

    @Override
    public void cleanStaleSubgraphIds() {
        //make sure there arent any stale subgraphIds, they do any harm but they do take up memory
        final Iterator<Object> idIterator = this.unreadableSubgraphIds.iterator();
        while (idIterator.hasNext()) {
            final Object id = idIterator.next();
            if (!this.isSubgraphIdUsed(id))
                idIterator.remove();
        }
    }

    private boolean isSubgraphIdUsed(final Object id) {
        return (this.getRawGraph().getVertex(id) != null);
    }


    private Features featureMerger() {
        final Features features = new Features();

        features.isWrapper = true;

        features.hasImplicitElements = false;
        features.ignoresSuppliedIds = true;
        features.isPersistent = false;
        features.supportsBooleanProperty = false;
        features.supportsDoubleProperty = false;
        features.supportsDuplicateEdges = false;
        features.supportsEdgeIndex = false;
        features.supportsEdgeIteration = false;
        features.supportsEdgeKeyIndex = false;
        features.supportsEdgeProperties = false;
        features.supportsEdgeRetrieval = false;
        features.supportsFloatProperty = false;
        features.supportsIndices = false;
        features.supportsIntegerProperty = false;
        features.supportsKeyIndices = false;
        features.supportsLongProperty = false;
        features.supportsMapProperty = false;
        features.supportsMixedListProperty = false;
        features.supportsPrimitiveArrayProperty = false;
        features.supportsSelfLoops = false;
        features.supportsSerializableObjectProperty = false;
        features.supportsStringProperty = false;
        features.supportsThreadedTransactions = false;
        features.supportsThreadIsolatedTransactions = false;
        features.supportsTransactions = false;
        features.supportsUniformListProperty = false;
        features.supportsVertexIndex = false;
        features.supportsVertexIteration = false;
        features.supportsVertexKeyIndex = false;
        features.supportsVertexProperties = false;

        final Iterator<? extends SubgraphVertex> subgraphIterator = this.getRawGraph().getFramedVertices(SubgraphVertex.class).iterator();

        while (subgraphIterator.hasNext()) {
            final Features subfeatures = subgraphIterator.next().getBaseGraph().getFeatures();
            if (subfeatures.hasImplicitElements == true)
                features.hasImplicitElements = true;
            if (subfeatures.ignoresSuppliedIds == false)
                features.ignoresSuppliedIds = false;
            if (subfeatures.isPersistent == true)
                features.isPersistent = true;
            if (subfeatures.supportsBooleanProperty == true)
                features.supportsBooleanProperty = true;
            if (subfeatures.supportsDoubleProperty == true)
                features.supportsDoubleProperty = true;
            if (subfeatures.supportsDuplicateEdges == true)
                features.supportsDuplicateEdges = true;
            if (subfeatures.supportsEdgeIndex == true)
                features.supportsEdgeIndex = true;
            if (subfeatures.supportsEdgeIteration == true)
                features.supportsEdgeIteration = true;
            if (subfeatures.supportsEdgeKeyIndex == true)
                features.supportsEdgeKeyIndex = true;
            if (subfeatures.supportsEdgeProperties == true)
                features.supportsEdgeProperties = true;
            if (subfeatures.supportsEdgeRetrieval == true)
                features.supportsEdgeRetrieval = true;
            if (subfeatures.supportsFloatProperty == true)
                features.supportsFloatProperty = true;
            if (subfeatures.supportsIndices == true)
                features.supportsIndices = true;
            if (subfeatures.supportsIntegerProperty == true)
                features.supportsIntegerProperty = true;
            if (subfeatures.supportsKeyIndices == true)
                features.supportsKeyIndices = true;
            if (subfeatures.supportsLongProperty == true)
                features.supportsLongProperty = true;
            if (subfeatures.supportsMapProperty == true)
                features.supportsMapProperty = true;
            if (subfeatures.supportsMixedListProperty == true)
                features.supportsMixedListProperty = true;
            if (subfeatures.supportsPrimitiveArrayProperty == true)
                features.supportsPrimitiveArrayProperty = true;
            if (subfeatures.supportsSelfLoops == true)
                features.supportsSelfLoops = true;
            if (subfeatures.supportsSerializableObjectProperty == true)
                features.supportsSerializableObjectProperty = true;
            if (subfeatures.supportsStringProperty == true)
                features.supportsStringProperty = true;
            if (subfeatures.supportsThreadedTransactions == true)
                features.supportsThreadedTransactions = true;
            if (subfeatures.supportsThreadIsolatedTransactions == true)
                features.supportsThreadIsolatedTransactions = true;
            if (subfeatures.supportsTransactions == true)
                features.supportsTransactions = true;
            if (subfeatures.supportsUniformListProperty == true)
                features.supportsUniformListProperty = true;
            if (subfeatures.supportsVertexIndex == true)
                features.supportsVertexIndex = true;
            if (subfeatures.supportsVertexIteration == true)
                features.supportsVertexIteration = true;
            if (subfeatures.supportsVertexKeyIndex == true)
                features.supportsVertexKeyIndex = true;
            if (subfeatures.supportsVertexProperties == true)
                features.supportsVertexProperties = true;
        }

        return features;
    }

    /**
     * A type of Element which encapsulates the element from a subgraph. This is the type of element returned to the user
     * when traversing this graph.
     *
     * @param <E> The type of element, this is usually either a Vertex or an Edge
     */
    private static abstract class NestedElement<E extends Element> implements Element {
        private final Object parentId;
        private final E delegate;

        public NestedElement(Object parentId, E delegate) {
            this.parentId = parentId;
            this.delegate = delegate;
        }

        public E getDelegate() {
            return delegate;
        }

        public Object getParentId() {
            return this.parentId;
        }

        @Override
        public <T> T getProperty(String key) {
            return delegate.getProperty(key);
        }

        @Override
        public Set<String> getPropertyKeys() {
            return delegate.getPropertyKeys();
        }

        @Override
        public void setProperty(String key, Object value) {
            delegate.setProperty(key, value);
        }

        @Override
        public <T> T removeProperty(String key) {
            return delegate.removeProperty(key);
        }

        @Override
        public MeshId getId() {
            return new NestedId(parentId, delegate.getId());
        }

        protected static class NestedId implements MeshId {
            private final Object subgraphId;
            private final Object subgraphVertexId;

            public NestedId(final Object subgraphId, final Object subgraphVertexId) {
                this.subgraphId = subgraphId;
                this.subgraphVertexId = subgraphVertexId;
            }

            @Override
            public Object getSubgraphId() {
                return this.subgraphId;
            }

            @Override
            public Object getSubgraphVertexId() {
                return this.subgraphVertexId;
            }

            @Override
            public int hashCode() {
                return Objects.hash(subgraphId, subgraphVertexId);
            }

            @Override
            public boolean equals(Object o) {
                if (this == o)
                    return true;

                if (!(o instanceof NestedId))
                    return false;

                final NestedId otherId = (NestedId) o;
                if (!subgraphId.equals(otherId.subgraphId))
                    return false;
                else if (!subgraphVertexId.equals(otherId.subgraphVertexId))
                    return false;
                else
                    return true;
            }

            @Override
            public String toString() {
                return new StringBuilder(subgraphId.toString()).append(".").append(subgraphVertexId).toString();
            }
        }
    }

    /**
     * An edge representing a link across subgraphs
     */
    private class LinkingEdge extends NestedElement<Edge> implements Edge {
        public LinkingEdge(final Edge delegate, final Object parentId) {
            super(parentId, delegate);
        }

        private String encodeKey(final String key) {
            //since we use a key called sublabel we want to preface any label with that name with a underscore,
            //similarly any variation having some number of prefixed underscores should have one underscore added.
            if(key.matches("[_]*sublabel"))
                return "_" + key;
            else
                return key;
        }

        private String decodeKey(final String key ) {
            //any keys encoded with a suffix of sublabel and underscores as a prefix needs translating
            if(key.matches("[_]*sublabel")) {
                //we want to hide the sublabel itself, since this is already the label of the edge.
                if( key.length() == 8)
                    return null;

                //the other labels we want to drop one of the prefixed underscores
                return key.replaceFirst("_", "");
            }
            //all the normal keys that dont need translating
            else
                return key;
        }

        @Override
        public <T> T getProperty(String key) {
            return super.getProperty(this.encodeKey(key));
        }

        @Override
        public Set<String> getPropertyKeys() {
            final Set<String> translatedKeys = new HashSet<>();
            for(final String key : super.getPropertyKeys()) {
                final String decodedKey = this.decodeKey(key);
                if(decodedKey == null)
                    continue;
                translatedKeys.add(decodedKey);
            }
            return Collections.unmodifiableSet(translatedKeys);
        }

        @Override
        public void setProperty(String key, Object value) {
            super.setProperty(this.encodeKey(key), value);
        }

        @Override
        public <T> T removeProperty(String key) {
            return super.removeProperty(this.encodeKey(key));
        }

        /**
         * Return the tail/out or head/in vertex.
         *
         * @param direction whether to return the tail/out or head/in vertex
         * @return the tail/out or head/in vertex
         * @throws IllegalArgumentException is thrown if a direction of both is provided
         */
        @Override
        public Vertex getVertex(Direction direction) throws IllegalArgumentException {
            // TODO : Implement this
            throw new UnsupportedOperationException("This method hasnt been implemented yet, comming soon!");
        }

        /**
         * Return the label associated with the edge.
         *
         * @return the edge label
         */
        @Override
        public String getLabel() {
            // TODO : Implement this
            throw new UnsupportedOperationException("This method hasnt been implemented yet, comming soon!");
        }

        /**
         * Remove the element from the graph.
         */
        @Override
        public void remove() {
            // TODO : Implement this
            throw new UnsupportedOperationException("This method hasnt been implemented yet, comming soon!");
        }
    }

    /**
     * A type of Edge which encapsulates the edge from a subgraph. This is the type of vertex returned to the user
     * when traversing this graph.
     */
    private class NestedEdge extends NestedElement<Edge> implements Edge {
        public NestedEdge(final Edge delegate, final Object parentId) {
            super(parentId, delegate);
        }

        /**
         * Return the tail/out or head/in vertex.
         *
         * @param direction whether to return the tail/out or head/in vertex
         * @return the tail/out or head/in vertex
         * @throws IllegalArgumentException is thrown if a direction of both is provided
         */
        @Override
        public Vertex getVertex(Direction direction) throws IllegalArgumentException {
            // TODO : Implement this
            throw new UnsupportedOperationException("This method hasnt been implemented yet, comming soon!");
        }

        /**
         * Return the label associated with the edge.
         *
         * @return the edge label
         */
        @Override
        public String getLabel() {
            // TODO : Implement this
            throw new UnsupportedOperationException("This method hasnt been implemented yet, comming soon!");
        }

        /**
         * Remove the element from the graph.
         */
        @Override
        public void remove() {
            // TODO : Implement this
            throw new UnsupportedOperationException("This method hasnt been implemented yet, comming soon!");
        }
    }

    /**
     * A type of Vertex which encapsulates the vertex from a subgraph. This is the type of vertex returned to the user
     * when traversing this graph.
     */
    private class NestedVertex extends NestedElement<Vertex> implements Vertex {
        public NestedVertex(final Vertex delegate, final Object parentId) {
            super(parentId, delegate);
        }

        @Override
        public void remove() {
            LinkedMeshGraph.this.removeVertex(this);
        }

        @Override
        public Iterable<Edge> getEdges(Direction direction, String... labels) {
            // TODO this needs to be fixed to encapsulate edges
            return this.getDelegate().getEdges(direction, labels);
        }

        @Override
        public Iterable<Vertex> getVertices(Direction direction, String... labels) {
            return new NestedVertexIterable(this.getDelegate().getVertices(direction, labels), this.getParentId());
        }

        @Override
        public VertexQuery query() {
            // TODO : Is this correct? Will it return proper encapsulated elements?
            return this.getDelegate().query();
        }

        @Override
        public Edge addEdge(String label, Vertex inVertex) {
            // TODO : This should be a NestedEdge
            return this.getDelegate().addEdge(label, inVertex);
        }
    }

    /**
     * An iterator object which encapsulates the iterator from a subgraph insuring it returns the proper element type
     */
    private class NestedVertexIterator implements Iterator<Vertex> {
        final Object parentId;
        final Iterator<Vertex> delegate;

        public NestedVertexIterator(Iterator<Vertex> delegate, Object parentId) {
            this.parentId = parentId;
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            return this.delegate.hasNext();
        }

        @Override
        public Vertex next() {
            return new NestedVertex(this.delegate.next(), this.parentId);
        }
    }

    /**
     * An iterable object which encapsulates the iterable from a subgraph insuring it returns the proper element type
     */
    private class NestedVertexIterable implements Iterable<Vertex> {
        final Object parentId;
        final Iterable<Vertex> delegate;

        public NestedVertexIterable(Iterable<Vertex> delegate, Object parentId) {
            this.parentId = parentId;
            this.delegate = delegate;
        }

        @Override
        public Iterator<Vertex> iterator() {
            return new NestedVertexIterator(this.delegate.iterator(), this.parentId);
        }
    }

    /**
     * An iterator which encapsulates the iterators provided by each of the subgraphs and unifies them into what appears
     * to the enduser to be a single iterator.
     */
    private static class SubgraphVertexIterator implements Iterator<Vertex> {
        private NestedVertexIterator currentIterator = null;
        final Set<NestedVertexIterator> subvertexIterators;

        public SubgraphVertexIterator(Set<NestedVertexIterator> subvertexIterators) {
            this.subvertexIterators = subvertexIterators;
        }

        @Override
        public boolean hasNext() {
            // TODO : lets try to get rrid of this while(true) it may be prone to infinite loops
            while (true) {
                if (currentIterator != null) {
                    if (currentIterator.hasNext())
                        return true;
                    else if (subvertexIterators.isEmpty())
                        return false;
                }

                currentIterator = subvertexIterators.iterator().next();
                subvertexIterators.remove(currentIterator);
            }
        }

        @Override
        public Vertex next() {
            // TODO : lets try to get rid of this while(true) it may be prone to infinite loops
            while (true) {
                if ((currentIterator != null) && (currentIterator.hasNext() || subvertexIterators.isEmpty()))
                    return currentIterator.next();

                currentIterator = subvertexIterators.iterator().next();
                subvertexIterators.remove(currentIterator);
            }
        }
    }

    /**
     * A cache for all the internal subgraphs. It limits the number of caches in the graph to a fixed amount and will
     * construct a new graph if the current graph is not present in the cache.
     */
    private class MeshGraphCache extends TreeGraphCache<Object, TransactionalGraph> {
        public MeshGraphCache() {
        }

        public MeshGraphCache(Integer maxGraphs) {
            super(maxGraphs);
        }

        @Override
        protected TransactionalGraph constructGraph(Object key) {
            pendingTransactions.add(getRawGraph());
            SubgraphVertex subgraphVertex = getRawGraph().getFramedVertices("id", key, SubgraphVertex.class).iterator().next();
            return subgraphVertex.getBaseGraph();
        }
    }
}
