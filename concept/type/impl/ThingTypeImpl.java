/*
 * Copyright (C) 2020 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package hypergraph.concept.type.impl;

import hypergraph.common.exception.Error;
import hypergraph.common.exception.HypergraphException;
import hypergraph.concept.thing.Thing;
import hypergraph.concept.thing.impl.AttributeImpl;
import hypergraph.concept.thing.impl.EntityImpl;
import hypergraph.concept.thing.impl.RelationImpl;
import hypergraph.concept.type.AttributeType;
import hypergraph.concept.type.RoleType;
import hypergraph.concept.type.ThingType;
import hypergraph.concept.type.Type;
import hypergraph.graph.TypeGraph;
import hypergraph.graph.util.Schema;
import hypergraph.graph.vertex.TypeVertex;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static hypergraph.common.exception.Error.TypeWrite.INVALID_KEY_VALUE_TYPE;
import static hypergraph.common.exception.Error.TypeWrite.INVALID_ROOT_TYPE_MUTATION;
import static hypergraph.common.iterator.Iterators.apply;
import static hypergraph.common.iterator.Iterators.filter;
import static hypergraph.common.iterator.Iterators.link;
import static hypergraph.common.iterator.Iterators.stream;
import static java.util.stream.Stream.concat;

public abstract class ThingTypeImpl extends TypeImpl implements ThingType {

    ThingTypeImpl(TypeVertex vertex) {
        super(vertex);
    }

    ThingTypeImpl(TypeGraph graph, String label, Schema.Vertex.Type schema) {
        super(graph, label, schema);
    }

    @Override
    public void isAbstract(boolean isAbstract) {
        vertex.isAbstract(isAbstract);
    }

    @Nullable
    public abstract ThingTypeImpl sup();

    private <T extends Type> void override(Schema.Edge.Type schema, T type, T overriddenType,
                                           Stream<? extends Type> overridable, Stream<? extends Type> notOverridable) {
        if (type.sups().noneMatch(t -> t.equals(overriddenType))) {
            throw new HypergraphException(Error.TypeWrite.INVALID_OVERRIDE_NOT_SUPERTYPE.format(type.label(), overriddenType.label()));
        } else if (notOverridable.anyMatch(t -> t.equals(overriddenType)) || overridable.noneMatch(t -> t.equals(overriddenType))) {
            throw new HypergraphException(Error.TypeWrite.INVALID_OVERRIDE_NOT_AVAILABLE.format(type.label(), overriddenType.label()));
        }

        vertex.outs().edge(schema, ((TypeImpl) type).vertex).overridden(((TypeImpl) overriddenType).vertex);
    }

    @Override
    public void key(AttributeType attributeType) {
        AttributeTypeImpl attributeTypeImpl = (AttributeTypeImpl) attributeType;
        if (!attributeType.isKeyable()) {
            throw new HypergraphException(INVALID_KEY_VALUE_TYPE.format(attributeTypeImpl.label(), attributeTypeImpl.valueType().getSimpleName()));
        } else if (vertex.outs().edge(Schema.Edge.Type.HAS, attributeTypeImpl.vertex) != null) {
            throw new HypergraphException("Invalid Key Assignment: " + attributeTypeImpl.label() + " is already used as an attribute");
        } else if (sups().filter(s -> !s.equals(this)).flatMap(ThingType::attributes).anyMatch(a -> a.equals(attributeTypeImpl))) {
            // TODO: should this be relaxed to just .flatMap(ThingType::keys) ?
            throw new HypergraphException("Invalid Attribute Assignment: " + attributeTypeImpl.label() + " is already inherited and/or overridden ");
        }

        vertex.outs().put(Schema.Edge.Type.KEY, attributeTypeImpl.vertex);
    }

    @Override
    public void key(AttributeType attributeType, AttributeType overriddenType) {
        this.key(attributeType);
        override(Schema.Edge.Type.KEY, attributeType, overriddenType,
                 sup().attributes(attributeType.valueType()),
                 declaredAttributes());
    }

    @Override
    public void unkey(AttributeType attributeType) {
        vertex.outs().edge(Schema.Edge.Type.KEY, ((AttributeTypeImpl) attributeType).vertex).delete();
    }

    @Override
    public Stream<AttributeTypeImpl> keys(Class<?> valueType) {
        return keys().filter(att -> att.valueType().equals(valueType));
    }

    @Override
    public Stream<AttributeTypeImpl> keys() {
        Stream<AttributeTypeImpl> keys = stream(apply(vertex.outs().edge(Schema.Edge.Type.KEY).to(), AttributeTypeImpl::of));
        if (isRoot()) {
            return keys;
        } else {
            Set<TypeVertex> overridden = new HashSet<>();
            filter(vertex.outs().edge(Schema.Edge.Type.KEY).overridden(), Objects::nonNull).forEachRemaining(overridden::add);
            return concat(keys, sup().keys().filter(key -> !overridden.contains(key.vertex)));
        }
    }

    @Override
    public void has(AttributeType attributeType) {
        AttributeTypeImpl attributeTypeImpl = (AttributeTypeImpl) attributeType;
        if (filter(vertex.outs().edge(Schema.Edge.Type.KEY).to(), v -> v.equals(attributeTypeImpl.vertex)).hasNext()) {
            throw new HypergraphException("Invalid Attribute Assignment: " + attributeTypeImpl.label() + " is already used as a key");
        } else if (sups().flatMap(ThingType::attributes).anyMatch(a -> a.equals(attributeTypeImpl))) {
            throw new HypergraphException("Invalid Attribute Assignment: " + attributeTypeImpl.label() + " is already inherited or overridden ");
        }

        vertex.outs().put(Schema.Edge.Type.HAS, attributeTypeImpl.vertex);
    }

    @Override
    public void has(AttributeType attributeType, AttributeType overriddenType) {
        this.has(attributeType);
        override(Schema.Edge.Type.HAS, attributeType, overriddenType,
                 sup().attributes(attributeType.valueType()),
                 concat(sup().keys(), declaredAttributes()));
    }

    @Override
    public void unhas(AttributeType attributeType) {
        vertex.outs().edge(Schema.Edge.Type.HAS, ((AttributeTypeImpl) attributeType).vertex).delete();
    }

    private Stream<AttributeTypeImpl> declaredAttributes() {
        return stream(link(
                vertex.outs().edge(Schema.Edge.Type.KEY).to(),
                vertex.outs().edge(Schema.Edge.Type.HAS).to()
        ).apply(AttributeTypeImpl::of));
    }

    @Override
    public Stream<AttributeTypeImpl> attributes(Class<?> valueType) {
        return attributes().filter(att -> att.valueType().equals(valueType));
    }

    @Override
    public Stream<AttributeTypeImpl> attributes() {
        if (isRoot()) {
            return declaredAttributes();
        } else {
            Set<TypeVertex> overridden = new HashSet<>();
            link(vertex.outs().edge(Schema.Edge.Type.KEY).overridden(),
                 vertex.outs().edge(Schema.Edge.Type.HAS).overridden()
            ).filter(Objects::nonNull).forEachRemaining(overridden::add);
            return concat(declaredAttributes(), sup().attributes().filter(att -> !overridden.contains(att.vertex)));
        }
    }

    @Override
    public void plays(RoleType roleType) {
        RoleTypeImpl roleTypeImpl = (RoleTypeImpl) roleType;
        if (sups().flatMap(ThingType::plays).anyMatch(a -> a.equals(roleTypeImpl))) {
            throw new HypergraphException("Invalid Attribute Assignment: " + roleTypeImpl.label() + " is already inherited or overridden ");
        }
        vertex.outs().put(Schema.Edge.Type.PLAYS, roleTypeImpl.vertex);
    }

    @Override
    public void plays(RoleType roleType, RoleType overriddenType) {
        plays(roleType);
        override(Schema.Edge.Type.PLAYS, roleType, overriddenType, sup().plays(), declaredPlays());
    }

    @Override
    public void unplay(RoleType roleType) {
        vertex.outs().edge(Schema.Edge.Type.PLAYS, ((RoleTypeImpl) roleType).vertex).delete();
    }

    private Stream<RoleTypeImpl> declaredPlays() {
        return stream(apply(vertex.outs().edge(Schema.Edge.Type.PLAYS).to(), RoleTypeImpl::of));
    }

    @Override
    public Stream<RoleTypeImpl> plays() {
        Stream<RoleTypeImpl> declared = stream(apply(vertex.outs().edge(Schema.Edge.Type.PLAYS).to(), RoleTypeImpl::of));
        if (isRoot()) {
            return declared;
        } else {
            Set<TypeVertex> overridden = new HashSet<>();
            filter(vertex.outs().edge(Schema.Edge.Type.PLAYS).overridden(), Objects::nonNull).forEachRemaining(overridden::add);
            return concat(declared, sup().plays().filter(att -> !overridden.contains(att.vertex)));
        }
    }

    @Override
    public void delete() {
        if (subs().anyMatch(s -> !s.equals(this))) {
            throw new HypergraphException("Invalid Type Removal: " + label() + " has subtypes");
        } else if (subs().flatMap(ThingType::instances).findAny().isPresent()) {
            throw new HypergraphException("Invalid Type Removal: " + label() + " has instances");
        } else {
            vertex.delete();
        }
    }

    @Override
    public void validate() {
        super.validate();
        // TODO: Add any validation that would apply to all ThingTypes here
    }

    public static class Root extends ThingTypeImpl {

        public Root(TypeVertex vertex) {
            super(vertex);
            assert vertex.label().equals(Schema.Vertex.Type.Root.THING.label());
        }

        @Override
        public boolean isRoot() { return true; }

        @Override
        public void label(String label) { throw new HypergraphException(INVALID_ROOT_TYPE_MUTATION); }

        @Override
        public void isAbstract(boolean isAbstract) { throw new HypergraphException(INVALID_ROOT_TYPE_MUTATION); }

        @Override
        public ThingTypeImpl sup() { return null; }

        @Override
        public Stream<? extends ThingTypeImpl> sups() {
            return Stream.of(this);
        }

        @Override
        public Stream<? extends ThingTypeImpl> subs() {
            return subs(v -> {
                switch (v.schema()) {
                    case THING_TYPE:
                        assert this.vertex == v;
                        return this;
                    case ENTITY_TYPE:
                        return EntityTypeImpl.of(v);
                    case ATTRIBUTE_TYPE:
                        return AttributeTypeImpl.of(v);
                    case RELATION_TYPE:
                        return RelationTypeImpl.of(v);
                    default:
                        throw new HypergraphException("Unreachable");
                }
            });
        }

        @Override
        public Stream<? extends Thing> instances() {
            return super.instances(v -> {
                switch (v.schema()) {
                    case ENTITY:
                        return EntityImpl.of(v);
                    case ATTRIBUTE:
                        return AttributeImpl.of(v);
                    case RELATION:
                        return RelationImpl.of(v);
                    default:
                        assert false;
                        throw new HypergraphException(Error.Internal.UNRECOGNISED_VALUE);
                }
            });
        }

        /**
         * No-op validation method of the root type 'thing'.
         *
         * There's nothing to validate for the root type 'thing'.
         */
        @Override
        public void validate() {}
    }
}
