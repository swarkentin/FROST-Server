/*
 * Copyright (C) 2016 Fraunhofer Institut IOSB, Fraunhoferstr. 1, D 76131
 * Karlsruhe, Germany.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.fraunhofer.iosb.ilt.sta.persistence.postgres;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.sql.RelationalPathBase;
import com.querydsl.sql.SQLQuery;
import com.querydsl.sql.SQLQueryFactory;
import de.fraunhofer.iosb.ilt.sta.model.id.Id;
import de.fraunhofer.iosb.ilt.sta.path.CustomPropertyPathElement;
import de.fraunhofer.iosb.ilt.sta.path.EntityPathElement;
import de.fraunhofer.iosb.ilt.sta.path.EntityProperty;
import de.fraunhofer.iosb.ilt.sta.path.EntitySetPathElement;
import de.fraunhofer.iosb.ilt.sta.path.EntityType;
import de.fraunhofer.iosb.ilt.sta.path.PropertyPathElement;
import de.fraunhofer.iosb.ilt.sta.path.ResourcePath;
import de.fraunhofer.iosb.ilt.sta.path.ResourcePathElement;
import de.fraunhofer.iosb.ilt.sta.path.ResourcePathVisitor;
import de.fraunhofer.iosb.ilt.sta.persistence.BasicPersistenceType;
import de.fraunhofer.iosb.ilt.sta.persistence.QDatastreams;
import de.fraunhofer.iosb.ilt.sta.persistence.QFeatures;
import de.fraunhofer.iosb.ilt.sta.persistence.QHistLocations;
import de.fraunhofer.iosb.ilt.sta.persistence.QLocations;
import de.fraunhofer.iosb.ilt.sta.persistence.QLocationsHistLocations;
import de.fraunhofer.iosb.ilt.sta.persistence.QObsProperties;
import de.fraunhofer.iosb.ilt.sta.persistence.QObservations;
import de.fraunhofer.iosb.ilt.sta.persistence.QSensors;
import de.fraunhofer.iosb.ilt.sta.persistence.QThings;
import de.fraunhofer.iosb.ilt.sta.persistence.QThingsLocations;
import de.fraunhofer.iosb.ilt.sta.query.OrderBy;
import de.fraunhofer.iosb.ilt.sta.query.Query;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author scf
 */
public class PathSqlBuilder implements ResourcePathVisitor {

    public static class TableRef {

        public EntityType type;
        public RelationalPathBase<?> qPath;

        public void clear() {
            type = null;
            qPath = null;
        }

        public TableRef copy() {
            TableRef copy = new TableRef();
            copy.type = this.type;
            copy.qPath = this.qPath;
            return copy;
        }

        public boolean isEmpty() {
            return type == null && qPath == null;
        }
    }
    /**
     * The logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(PathSqlBuilder.class);
    /**
     * The prefix used for table aliases. The main entity is always
     * &lt;PREFIX&gt;1.
     */
    public static final String ALIAS_PREFIX = "e";
    private SQLQueryFactory queryFactory;
    private SQLQuery<Tuple> sqlQuery;
    private List<EntityProperty> selectedProperties;
    private TableRef lastPath = new TableRef();
    private TableRef mainTable;
    private int aliasNr = 0;

    public synchronized SQLQuery<Tuple> buildFor(ResourcePath path, Query query, SQLQueryFactory sqlQueryFactory) {
        this.queryFactory = sqlQueryFactory;
        selectedProperties = new ArrayList<>();
        sqlQuery = queryFactory.select(new Expression<?>[]{});
        sqlQuery.distinct();
        lastPath.clear();
        aliasNr = 0;
        List<ResourcePathElement> elements = new ArrayList<>(path.getPathElements());

        int count = elements.size();
        for (int i = count - 1; i >= 0; i--) {
            ResourcePathElement element = elements.get(i);
            element.visit(this);
        }
        if (query != null) {
            PgExpressionHandler handler = new PgExpressionHandler(this, mainTable.copy());
            for (OrderBy ob : query.getOrderBy()) {
                handler.addOrderbyToQuery(ob, sqlQuery);
            }
            de.fraunhofer.iosb.ilt.sta.query.expression.Expression filter = query.getFilter();
            if (filter != null) {
                handler.addFilterToQuery(filter, sqlQuery);
            }
        }

        return sqlQuery;
    }

    @Override
    public void visit(EntityPathElement element) {
        Long intId = null;
        Id id = element.getId();
        if (id != null) {
            if (id.getBasicPersistenceType() == BasicPersistenceType.Integer) {
                intId = (Long) id.asBasicPersistenceType();
            } else {
                throw new IllegalArgumentException("This implementation expects Long ids, not " + id.getBasicPersistenceType());
            }
        }
        queryEntityType(element.getEntityType(), intId, lastPath);
    }

    @Override
    public void visit(EntitySetPathElement element) {
        queryEntityType(element.getEntityType(), null, lastPath);
    }

    @Override
    public void visit(PropertyPathElement element) {
        selectedProperties.add(element.getProperty());
    }

    @Override
    public void visit(CustomPropertyPathElement element) {
        // noting to do for cusom properties.
    }

    public void queryEntityType(EntityType type, Long id, TableRef last) {
        switch (type) {
            case Datastream:
                queryDatastreams(id, last);
                break;

            case FeatureOfInterest:
                queryFeatures(id, last);
                break;

            case HistoricalLocation:
                queryHistLocations(id, last);
                break;

            case Location:
                queryLocations(id, last);
                break;

            case Observation:
                queryObservations(id, last);
                break;

            case ObservedProperty:
                queryObsProperties(id, last);
                break;

            case Sensor:
                querySensors(id, last);
                break;

            case Thing:
                queryThings(id, last);
                break;

            default:
                LOGGER.error("Unknown entity type {}!?", type);
                throw new IllegalStateException("Unknown entity type " + type);
        }
        if (mainTable == null && !last.isEmpty()) {
            mainTable = last.copy();
        }

    }

    private void queryDatastreams(Long entityId, TableRef last) {
        int nr = ++aliasNr;
        String alias = ALIAS_PREFIX + nr;
        QDatastreams qDataStreams = new QDatastreams(alias);
        boolean added = true;
        if (last.type == null) {
            sqlQuery.select(PropertyHelper.getExpressions(qDataStreams, selectedProperties));
            sqlQuery.from(qDataStreams);
        } else {
            switch (last.type) {
                case Thing:
                    QThings qThings = (QThings) last.qPath;
                    sqlQuery.innerJoin(qDataStreams).on(qDataStreams.thingId.eq(qThings.id));
                    break;

                case Observation:
                    QObservations qObservations = (QObservations) last.qPath;
                    sqlQuery.innerJoin(qDataStreams).on(qDataStreams.id.eq(qObservations.datastreamId));
                    break;

                case Sensor:
                    QSensors qSensors = (QSensors) last.qPath;
                    sqlQuery.innerJoin(qDataStreams).on(qDataStreams.sensorId.eq(qSensors.id));
                    break;

                case ObservedProperty:
                    QObsProperties qObsProperties = (QObsProperties) last.qPath;
                    sqlQuery.innerJoin(qDataStreams).on(qDataStreams.obsPropertyId.eq(qObsProperties.id));
                    break;

                case Datastream:
                    added = false;
                    break;

                default:
                    LOGGER.error("Do not know how to join {} onto Datastreams.", last.type);
                    throw new IllegalStateException("Do not know how to join");
            }
        }
        if (added) {
            last.type = EntityType.Datastream;
            last.qPath = qDataStreams;
        }
        if (entityId != null) {
            sqlQuery.where(qDataStreams.id.eq(entityId));
        }
    }

    private void queryThings(Long entityId, TableRef last) {
        int nr = ++aliasNr;
        String alias = ALIAS_PREFIX + nr;
        QThings qThings = new QThings(alias);
        boolean added = true;
        if (last.type == null) {
            sqlQuery.select(PropertyHelper.getExpressions(qThings, selectedProperties));
            sqlQuery.from(qThings);
        } else {
            switch (last.type) {
                case Datastream:
                    QDatastreams dataStreams = (QDatastreams) last.qPath;
                    sqlQuery.innerJoin(qThings).on(qThings.id.eq(dataStreams.thingId));
                    break;

                case HistoricalLocation:
                    QHistLocations qHistLocations = (QHistLocations) last.qPath;
                    sqlQuery.innerJoin(qThings).on(qThings.id.eq(qHistLocations.thingId));
                    break;

                case Location:
                    QLocations qLocations = (QLocations) last.qPath;
                    QThingsLocations qTL = new QThingsLocations(alias + "j1");
                    sqlQuery.innerJoin(qTL).on(qLocations.id.eq(qTL.locationId));
                    sqlQuery.innerJoin(qThings).on(qThings.id.eq(qTL.thingId));
                    break;

                case Thing:
                    added = false;
                    break;

                default:
                    LOGGER.error("Do not know how to join {} onto Things.", last.type);
                    throw new IllegalStateException("Do not know how to join");
            }
        }
        if (added) {
            last.type = EntityType.Thing;
            last.qPath = qThings;
        }
        if (entityId != null) {
            sqlQuery.where(qThings.id.eq(entityId));
        }
    }

    private void queryFeatures(Long entityId, TableRef last) {
        int nr = ++aliasNr;
        String alias = ALIAS_PREFIX + nr;
        QFeatures qFeatures = new QFeatures(alias);
        boolean added = true;
        if (last.type == null) {
            sqlQuery.select(PropertyHelper.getExpressions(qFeatures, selectedProperties));
            sqlQuery.from(qFeatures);
        } else {
            switch (last.type) {
                case Observation:
                    QObservations qObservations = (QObservations) last.qPath;
                    sqlQuery.innerJoin(qFeatures).on(qFeatures.id.eq(qObservations.featureId));
                    break;

                case FeatureOfInterest:
                    added = false;
                    break;

                default:
                    LOGGER.error("Do not know how to join {} onto Features.", last.type);
                    throw new IllegalStateException("Do not know how to join");
            }
        }
        if (added) {
            last.type = EntityType.FeatureOfInterest;
            last.qPath = qFeatures;
        }
        if (entityId != null) {
            sqlQuery.where(qFeatures.id.eq(entityId));
        }
    }

    private void queryHistLocations(Long entityId, TableRef last) {
        int nr = ++aliasNr;
        String alias = ALIAS_PREFIX + nr;
        QHistLocations qHistLocations = new QHistLocations(alias);
        boolean added = true;
        if (last.type == null) {
            sqlQuery.select(PropertyHelper.getExpressions(qHistLocations, selectedProperties));
            sqlQuery.from(qHistLocations);
        } else {
            switch (last.type) {
                case Thing:
                    QThings qThings = (QThings) last.qPath;
                    sqlQuery.innerJoin(qHistLocations).on(qThings.id.eq(qHistLocations.thingId));
                    break;

                case Location:
                    QLocations qLocations = (QLocations) last.qPath;
                    QLocationsHistLocations qLHL = new QLocationsHistLocations(alias + "j1");
                    sqlQuery.innerJoin(qLHL).on(qLocations.id.eq(qLHL.locationId));
                    sqlQuery.innerJoin(qHistLocations).on(qHistLocations.id.eq(qLHL.histLocationId));
                    break;

                case HistoricalLocation:
                    added = false;
                    break;

                default:
                    LOGGER.error("Do not know how to join {} onto HistLocations.", last.type);
                    throw new IllegalStateException("Do not know how to join");
            }
        }
        if (added) {
            last.type = EntityType.HistoricalLocation;
            last.qPath = qHistLocations;
        }
        if (entityId != null) {
            sqlQuery.where(qHistLocations.id.eq(entityId));
        }
    }

    private void queryLocations(Long entityId, TableRef last) {
        int nr = ++aliasNr;
        String alias = ALIAS_PREFIX + nr;
        QLocations qLocations = new QLocations(alias);
        boolean added = true;
        if (last.type == null) {
            sqlQuery.select(PropertyHelper.getExpressions(qLocations, selectedProperties));
            sqlQuery.from(qLocations);
        } else {
            switch (last.type) {
                case Thing:
                    QThings qThings = (QThings) last.qPath;
                    QThingsLocations qTL = new QThingsLocations(alias + "j1");
                    sqlQuery.innerJoin(qTL).on(qThings.id.eq(qTL.thingId));
                    sqlQuery.innerJoin(qLocations).on(qLocations.id.eq(qTL.locationId));
                    break;

                case HistoricalLocation:
                    QHistLocations qHistLocations = (QHistLocations) last.qPath;
                    QLocationsHistLocations qLHL = new QLocationsHistLocations(alias + "j1");
                    sqlQuery.innerJoin(qLHL).on(qHistLocations.id.eq(qLHL.histLocationId));
                    sqlQuery.innerJoin(qLocations).on(qLocations.id.eq(qLHL.locationId));
                    break;

                case Location:
                    added = false;
                    break;

                default:
                    LOGGER.error("Do not know how to join {} onto Locations.", last.type);
                    throw new IllegalStateException("Do not know how to join");
            }
        }
        if (added) {
            last.type = EntityType.Location;
            last.qPath = qLocations;
        }
        if (entityId != null) {
            sqlQuery.where(qLocations.id.eq(entityId));
        }
    }

    private void querySensors(Long entityId, TableRef last) {
        int nr = ++aliasNr;
        String alias = ALIAS_PREFIX + nr;
        QSensors qSensors = new QSensors(alias);
        boolean added = true;
        if (last.type == null) {
            sqlQuery.select(PropertyHelper.getExpressions(qSensors, selectedProperties));
            sqlQuery.from(qSensors);
        } else {
            switch (last.type) {
                case Datastream:
                    QDatastreams qDatastreams = (QDatastreams) last.qPath;
                    sqlQuery.innerJoin(qSensors).on(qSensors.id.eq(qDatastreams.sensorId));
                    break;

                case Sensor:
                    added = false;
                    break;

                default:
                    LOGGER.error("Do not know how to join {} onto Sensors.", last.type);
                    throw new IllegalStateException("Do not know how to join");
            }
        }
        if (added) {
            last.type = EntityType.Sensor;
            last.qPath = qSensors;
        }
        if (entityId != null) {
            sqlQuery.where(qSensors.id.eq(entityId));
        }
    }

    private void queryObservations(Long entityId, TableRef last) {
        int nr = ++aliasNr;
        String alias = ALIAS_PREFIX + nr;
        QObservations qObservations = new QObservations(alias);
        boolean added = true;
        if (last.type == null) {
            sqlQuery.select(PropertyHelper.getExpressions(qObservations, selectedProperties));
            sqlQuery.from(qObservations);
        } else {
            switch (last.type) {
                case FeatureOfInterest:
                    QFeatures qFeatures = (QFeatures) last.qPath;
                    sqlQuery.innerJoin(qObservations).on(qFeatures.id.eq(qObservations.featureId));
                    break;

                case Datastream:
                    QDatastreams qDatastreams = (QDatastreams) last.qPath;
                    sqlQuery.innerJoin(qObservations).on(qDatastreams.id.eq(qObservations.datastreamId));
                    break;

                case Observation:
                    added = false;
                    break;

                default:
                    LOGGER.error("Do not know how to join {} onto Observations.", last.type);
                    throw new IllegalStateException("Do not know how to join");
            }
        }
        if (added) {
            last.type = EntityType.Observation;
            last.qPath = qObservations;
        }
        if (entityId != null) {
            sqlQuery.where(qObservations.id.eq(entityId));
        }
    }

    private void queryObsProperties(Long entityId, TableRef last) {
        int nr = ++aliasNr;
        String alias = ALIAS_PREFIX + nr;
        QObsProperties qObsProperties = new QObsProperties(alias);
        boolean added = true;
        if (last.type == null) {
            sqlQuery.select(PropertyHelper.getExpressions(qObsProperties, selectedProperties));
            sqlQuery.from(qObsProperties);
        } else {
            switch (last.type) {
                case Datastream:
                    QDatastreams qDatastreams = (QDatastreams) last.qPath;
                    sqlQuery.innerJoin(qObsProperties).on(qObsProperties.id.eq(qDatastreams.obsPropertyId));
                    break;
                case ObservedProperty:
                    added = false;
                    break;

                default:
                    LOGGER.error("Do not know how to join {} onto ObsProperties.", last.type);
                    throw new IllegalStateException("Do not know how to join");
            }
        }
        if (added) {
            last.type = EntityType.ObservedProperty;
            last.qPath = qObsProperties;
        }
        if (entityId != null) {
            sqlQuery.where(qObsProperties.id.eq(entityId));
        }
    }

}
