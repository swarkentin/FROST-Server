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
package de.fraunhofer.iosb.ilt.sta.persistence.postgres.longid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.querydsl.core.Tuple;
import com.querydsl.core.dml.StoreClause;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.DateTimePath;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringPath;
import com.querydsl.spatial.GeometryPath;
import com.querydsl.sql.SQLQuery;
import com.querydsl.sql.SQLQueryFactory;
import com.querydsl.sql.dml.SQLInsertClause;
import com.querydsl.sql.dml.SQLUpdateClause;
import de.fraunhofer.iosb.ilt.sta.json.deserialize.EntityParser;
import de.fraunhofer.iosb.ilt.sta.json.deserialize.custom.GeoJsonDeserializier;
import de.fraunhofer.iosb.ilt.sta.json.serialize.GeoJsonSerializer;
import de.fraunhofer.iosb.ilt.sta.messagebus.EntityChangedMessage;
import de.fraunhofer.iosb.ilt.sta.model.Datastream;
import de.fraunhofer.iosb.ilt.sta.model.FeatureOfInterest;
import de.fraunhofer.iosb.ilt.sta.model.HistoricalLocation;
import de.fraunhofer.iosb.ilt.sta.model.Location;
import de.fraunhofer.iosb.ilt.sta.model.MultiDatastream;
import de.fraunhofer.iosb.ilt.sta.model.Observation;
import de.fraunhofer.iosb.ilt.sta.model.ObservedProperty;
import de.fraunhofer.iosb.ilt.sta.model.Sensor;
import de.fraunhofer.iosb.ilt.sta.model.Thing;
import de.fraunhofer.iosb.ilt.sta.model.builder.DatastreamBuilder;
import de.fraunhofer.iosb.ilt.sta.model.builder.FeatureOfInterestBuilder;
import de.fraunhofer.iosb.ilt.sta.model.builder.MultiDatastreamBuilder;
import de.fraunhofer.iosb.ilt.sta.model.builder.SensorBuilder;
import de.fraunhofer.iosb.ilt.sta.model.builder.ThingBuilder;
import de.fraunhofer.iosb.ilt.sta.model.core.Entity;
import de.fraunhofer.iosb.ilt.sta.model.core.EntitySet;
import de.fraunhofer.iosb.ilt.sta.model.core.Id;
import de.fraunhofer.iosb.ilt.sta.model.core.IdLong;
import de.fraunhofer.iosb.ilt.sta.model.ext.TimeInstant;
import de.fraunhofer.iosb.ilt.sta.model.ext.TimeInterval;
import de.fraunhofer.iosb.ilt.sta.model.ext.TimeValue;
import de.fraunhofer.iosb.ilt.sta.model.ext.UnitOfMeasurement;
import de.fraunhofer.iosb.ilt.sta.path.EntityProperty;
import de.fraunhofer.iosb.ilt.sta.path.EntitySetPathElement;
import de.fraunhofer.iosb.ilt.sta.path.EntityType;
import de.fraunhofer.iosb.ilt.sta.path.NavigationProperty;
import de.fraunhofer.iosb.ilt.sta.path.ResourcePath;
import de.fraunhofer.iosb.ilt.sta.persistence.postgres.ResultType;
import de.fraunhofer.iosb.ilt.sta.util.IncompleteEntityException;
import de.fraunhofer.iosb.ilt.sta.util.NoSuchEntityException;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.geojson.Crs;
import org.geojson.Feature;
import org.geojson.GeoJsonObject;
import org.geojson.jackson.CrsType;
import org.geolatte.common.dataformats.json.jackson.JsonException;
import org.geolatte.common.dataformats.json.jackson.JsonMapper;
import org.geolatte.geom.Geometry;
import org.joda.time.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Hylke van der Schaaf
 */
public class EntityInserter {

    /**
     * The logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityInserter.class);
    private final PostgresPersistenceManagerLong pm;
    private ObjectMapper formatter;

    public EntityInserter(PostgresPersistenceManagerLong pm) {
        this.pm = pm;
    }

    public boolean insertDatastream(Datastream ds) throws NoSuchEntityException, IncompleteEntityException {
        // First check ObservedPropery, Sensor and Thing
        ObservedProperty op = ds.getObservedProperty();
        entityExistsOrCreate(op);

        Sensor s = ds.getSensor();
        entityExistsOrCreate(s);

        Thing t = ds.getThing();
        entityExistsOrCreate(t);

        SQLQueryFactory qFactory = pm.createQueryFactory();

        QDatastreams qd = QDatastreams.datastreams;
        SQLInsertClause insert = qFactory.insert(qd);
        insert.set(qd.name, ds.getName());
        insert.set(qd.description, ds.getDescription());
        insert.set(qd.observationType, ds.getObservationType());
        insert.set(qd.unitDefinition, ds.getUnitOfMeasurement().getDefinition());
        insert.set(qd.unitName, ds.getUnitOfMeasurement().getName());
        insert.set(qd.unitSymbol, ds.getUnitOfMeasurement().getSymbol());
        insert.set(qd.properties, objectToJson(ds.getProperties()));

        insert.set(qd.phenomenonTimeStart, new Timestamp(PostgresPersistenceManagerLong.DATETIME_MAX.getMillis()));
        insert.set(qd.phenomenonTimeEnd, new Timestamp(PostgresPersistenceManagerLong.DATETIME_MIN.getMillis()));
        insert.set(qd.resultTimeStart, new Timestamp(PostgresPersistenceManagerLong.DATETIME_MAX.getMillis()));
        insert.set(qd.resultTimeEnd, new Timestamp(PostgresPersistenceManagerLong.DATETIME_MIN.getMillis()));

        insert.set(qd.obsPropertyId, (Long) op.getId().getValue());
        insert.set(qd.sensorId, (Long) s.getId().getValue());
        insert.set(qd.thingId, (Long) t.getId().getValue());

        insertUserDefinedId(insert, qd.id, ds);

        Long datastreamId = insert.executeWithKey(qd.id);
        LOGGER.debug("Inserted datastream. Created id = {}.", datastreamId);
        ds.setId(new IdLong(datastreamId));

        // Create Observations, if any.
        for (Observation o : ds.getObservations()) {
            o.setDatastream(new DatastreamBuilder().setId(ds.getId()).build());
            o.complete();
            pm.insert(o);
        }

        return true;
    }

    public EntityChangedMessage updateDatastream(Datastream d, long dsId) throws NoSuchEntityException {
        SQLQueryFactory qFactory = pm.createQueryFactory();
        QDatastreams qd = QDatastreams.datastreams;
        SQLUpdateClause update = qFactory.update(qd);
        EntityChangedMessage message = new EntityChangedMessage();

        if (d.isSetName()) {
            if (d.getName() == null) {
                throw new IncompleteEntityException("name can not be null.");
            }
            update.set(qd.name, d.getName());
            message.addField(EntityProperty.NAME);
        }
        if (d.isSetDescription()) {
            if (d.getDescription() == null) {
                throw new IncompleteEntityException("description can not be null.");
            }
            update.set(qd.description, d.getDescription());
            message.addField(EntityProperty.DESCRIPTION);
        }
        if (d.isSetObservationType()) {
            if (d.getObservationType() == null) {
                throw new IncompleteEntityException("observationType can not be null.");
            }
            update.set(qd.observationType, d.getObservationType());
            message.addField(EntityProperty.OBSERVATIONTYPE);
        }
        if (d.isSetProperties()) {
            update.set(qd.properties, objectToJson(d.getProperties()));
            message.addField(EntityProperty.PROPERTIES);
        }
        if (d.isSetObservedProperty()) {
            if (!entityExists(d.getObservedProperty())) {
                throw new NoSuchEntityException("ObservedProperty with no id or not found.");
            }
            update.set(qd.obsPropertyId, (Long) d.getObservedProperty().getId().getValue());
            message.addField(NavigationProperty.OBSERVEDPROPERTY);
        }
        if (d.isSetSensor()) {
            if (!entityExists(d.getSensor())) {
                throw new NoSuchEntityException("Sensor with no id or not found.");
            }
            update.set(qd.sensorId, (Long) d.getSensor().getId().getValue());
            message.addField(NavigationProperty.SENSOR);
        }
        if (d.isSetThing()) {
            if (!entityExists(d.getThing())) {
                throw new NoSuchEntityException("Thing with no id or not found.");
            }
            update.set(qd.thingId, (Long) d.getThing().getId().getValue());
            message.addField(NavigationProperty.THING);
        }
        if (d.isSetUnitOfMeasurement()) {
            if (d.getUnitOfMeasurement() == null) {
                throw new IncompleteEntityException("unitOfMeasurement can not be null.");
            }
            UnitOfMeasurement uom = d.getUnitOfMeasurement();
            update.set(qd.unitDefinition, uom.getDefinition());
            update.set(qd.unitName, uom.getName());
            update.set(qd.unitSymbol, uom.getSymbol());
            message.addField(EntityProperty.UNITOFMEASUREMENT);
        }

        update.where(qd.id.eq(dsId));
        long count = 0;
        if (!update.isEmpty()) {
            count = update.execute();
        }
        if (count > 1) {
            LOGGER.error("Updating Datastream {} caused {} rows to change!", dsId, count);
            throw new IllegalStateException("Update changed multiple rows.");
        }

        // Link existing Observations to the Datastream.
        for (Observation o : d.getObservations()) {
            if (o.getId() == null || !entityExists(o)) {
                throw new NoSuchEntityException("Observation with no id or non existing.");
            }
            Long obsId = (Long) o.getId().getValue();
            QObservations qo = QObservations.observations;
            long oCount = qFactory.update(qo)
                    .set(qo.datastreamId, dsId)
                    .where(qo.id.eq(obsId))
                    .execute();
            if (oCount > 0) {
                LOGGER.debug("Assigned datastream {} to Observation {}.", dsId, obsId);
            }
        }

        LOGGER.debug("Updated Datastream {}", dsId);
        return message;
    }

    public boolean insertMultiDatastream(MultiDatastream ds) throws NoSuchEntityException, IncompleteEntityException {
        // First check Sensor and Thing
        Sensor s = ds.getSensor();
        entityExistsOrCreate(s);

        Thing t = ds.getThing();
        entityExistsOrCreate(t);

        SQLQueryFactory qFactory = pm.createQueryFactory();

        QMultiDatastreams qd = QMultiDatastreams.multiDatastreams;
        SQLInsertClause insert = qFactory.insert(qd);
        insert.set(qd.name, ds.getName());
        insert.set(qd.description, ds.getDescription());
        insert.set(qd.observationTypes, objectToJson(ds.getMultiObservationDataTypes()));
        insert.set(qd.unitOfMeasurements, objectToJson(ds.getUnitOfMeasurements()));
        insert.set(qd.properties, objectToJson(ds.getProperties()));

        insert.set(qd.phenomenonTimeStart, new Timestamp(PostgresPersistenceManagerLong.DATETIME_MAX.getMillis()));
        insert.set(qd.phenomenonTimeEnd, new Timestamp(PostgresPersistenceManagerLong.DATETIME_MIN.getMillis()));
        insert.set(qd.resultTimeStart, new Timestamp(PostgresPersistenceManagerLong.DATETIME_MAX.getMillis()));
        insert.set(qd.resultTimeEnd, new Timestamp(PostgresPersistenceManagerLong.DATETIME_MIN.getMillis()));

        insert.set(qd.sensorId, (Long) s.getId().getValue());
        insert.set(qd.thingId, (Long) t.getId().getValue());

        insertUserDefinedId(insert, qd.id, ds);

        Long multiDatastreamId = insert.executeWithKey(qd.id);
        LOGGER.debug("Inserted multiDatastream. Created id = {}.", multiDatastreamId);
        ds.setId(new IdLong(multiDatastreamId));

        // Create new Locations, if any.
        EntitySet<ObservedProperty> ops = ds.getObservedProperties();
        int rank = 0;
        for (ObservedProperty op : ops) {
            entityExistsOrCreate(op);
            Long opId = (Long) op.getId().getValue();

            QMultiDatastreamsObsProperties qMdOp = QMultiDatastreamsObsProperties.multiDatastreamsObsProperties;
            insert = qFactory.insert(qMdOp);
            insert.set(qMdOp.multiDatastreamId, multiDatastreamId);
            insert.set(qMdOp.obsPropertyId, opId);
            insert.set(qMdOp.rank, rank);
            insert.execute();
            LOGGER.debug("Linked MultiDatastream {} to ObservedProperty {} with rank {}.", multiDatastreamId, opId, rank);
            rank++;
        }

        // Create Observations, if any.
        for (Observation o : ds.getObservations()) {
            o.setMultiDatastream(new MultiDatastreamBuilder().setId(ds.getId()).build());
            o.complete();
            pm.insert(o);
        }

        return true;
    }

    public EntityChangedMessage updateMultiDatastream(MultiDatastream d, long dsId) throws NoSuchEntityException {
        SQLQueryFactory qFactory = pm.createQueryFactory();
        QMultiDatastreams qd = QMultiDatastreams.multiDatastreams;
        SQLUpdateClause update = qFactory.update(qd);
        EntityChangedMessage message = new EntityChangedMessage();

        if (d.isSetName()) {
            if (d.getName() == null) {
                throw new IncompleteEntityException("name can not be null.");
            }
            update.set(qd.name, d.getName());
            message.addField(EntityProperty.NAME);
        }
        if (d.isSetDescription()) {
            if (d.getDescription() == null) {
                throw new IncompleteEntityException("description can not be null.");
            }
            update.set(qd.description, d.getDescription());
            message.addField(EntityProperty.DESCRIPTION);
        }
        if (d.isSetProperties()) {
            update.set(qd.properties, objectToJson(d.getProperties()));
            message.addField(EntityProperty.PROPERTIES);
        }

        if (d.isSetSensor()) {
            if (!entityExists(d.getSensor())) {
                throw new NoSuchEntityException("Sensor with no id or not found.");
            }
            update.set(qd.sensorId, (Long) d.getSensor().getId().getValue());
            message.addField(NavigationProperty.SENSOR);
        }
        if (d.isSetThing()) {
            if (!entityExists(d.getThing())) {
                throw new NoSuchEntityException("Thing with no id or not found.");
            }
            update.set(qd.thingId, (Long) d.getThing().getId().getValue());
            message.addField(NavigationProperty.THING);
        }

        MultiDatastream original = (MultiDatastream) pm.get(EntityType.MULTIDATASTREAM, new IdLong(dsId));
        int countOrig = original.getMultiObservationDataTypes().size();

        int countUom = countOrig;
        if (d.isSetUnitOfMeasurements()) {
            if (d.getUnitOfMeasurements() == null) {
                throw new IncompleteEntityException("unitOfMeasurements can not be null.");
            }
            List<UnitOfMeasurement> uoms = d.getUnitOfMeasurements();
            countUom = uoms.size();
            update.set(qd.unitOfMeasurements, objectToJson(uoms));
            message.addField(EntityProperty.UNITOFMEASUREMENTS);
        }
        int countDataTypes = countOrig;
        if (d.isSetMultiObservationDataTypes()) {
            List<String> dataTypes = d.getMultiObservationDataTypes();
            if (dataTypes == null) {
                throw new IncompleteEntityException("multiObservationDataTypes can not be null.");
            }
            countDataTypes = dataTypes.size();
            update.set(qd.observationTypes, objectToJson(dataTypes));
            message.addField(EntityProperty.MULTIOBSERVATIONDATATYPES);
        }
        EntitySet<ObservedProperty> ops = d.getObservedProperties();
        int countOps = countOrig + ops.size();
        for (ObservedProperty op : ops) {
            if (op.getId() == null || !entityExists(op)) {
                throw new NoSuchEntityException("ObservedProperty with no id or not found.");
            }
        }

        if (countUom != countDataTypes) {
            throw new IllegalArgumentException("New number of unitOfMeasurements does not match new number of multiObservationDataTypes.");
        }
        if (countUom != countOps) {
            throw new IllegalArgumentException("New number of unitOfMeasurements does not match new number of ObservedProperties.");
        }

        update.where(qd.id.eq(dsId));
        long count = 0;
        if (!update.isEmpty()) {
            count = update.execute();
        }
        if (count > 1) {
            LOGGER.error("Updating Datastream {} caused {} rows to change!", dsId, count);
            throw new IllegalStateException("Update changed multiple rows.");
        }

        // Link existing ObservedProperties to the MultiDatastream.
        int rank = countOrig;
        for (ObservedProperty op : ops) {
            Long opId = (Long) op.getId().getValue();
            QMultiDatastreamsObsProperties qMdOp = QMultiDatastreamsObsProperties.multiDatastreamsObsProperties;
            long oCount = qFactory.insert(qMdOp)
                    .set(qMdOp.multiDatastreamId, dsId)
                    .set(qMdOp.obsPropertyId, opId)
                    .set(qMdOp.rank, rank)
                    .execute();
            if (oCount > 0) {
                LOGGER.debug("Assigned datastream {} to ObservedProperty {} with rank {}.", dsId, opId, rank);
            }
            rank++;
        }

        // Link existing Observations to the MultiDatastream.
        for (Observation o : d.getObservations()) {
            if (o.getId() == null || !entityExists(o)) {
                throw new NoSuchEntityException("Observation with no id or non existing.");
            }
            Long obsId = (Long) o.getId().getValue();
            QObservations qo = QObservations.observations;
            long oCount = qFactory.update(qo)
                    .set(qo.datastreamId, dsId)
                    .where(qo.id.eq(obsId))
                    .execute();
            if (oCount > 0) {
                LOGGER.debug("Assigned datastream {} to Observation {}.", dsId, obsId);
            }
        }

        LOGGER.debug("Updated Datastream {}", dsId);
        return message;
    }

    public boolean insertFeatureOfInterest(FeatureOfInterest foi) throws NoSuchEntityException {
        // No linked entities to check first.
        SQLQueryFactory qFactory = pm.createQueryFactory();
        QFeatures qfoi = QFeatures.features;
        SQLInsertClause insert = qFactory.insert(qfoi);
        insert.set(qfoi.name, foi.getName());
        insert.set(qfoi.description, foi.getDescription());
        insert.set(qfoi.properties, objectToJson(foi.getProperties()));

        String encodingType = foi.getEncodingType();
        insert.set(qfoi.encodingType, encodingType);
        insertGeometry(insert, qfoi.feature, qfoi.geom, encodingType, foi.getFeature());

        insertUserDefinedId(insert, qfoi.id, foi);

        Long generatedId = insert.executeWithKey(qfoi.id);
        LOGGER.debug("Inserted FeatureOfInterest. Created id = {}.", generatedId);
        foi.setId(new IdLong(generatedId));
        return true;
    }

    public EntityChangedMessage updateFeatureOfInterest(FeatureOfInterest foi, long foiId) throws NoSuchEntityException {
        SQLQueryFactory qFactory = pm.createQueryFactory();
        QFeatures qfoi = QFeatures.features;
        SQLUpdateClause update = qFactory.update(qfoi);
        EntityChangedMessage message = new EntityChangedMessage();

        if (foi.isSetName()) {
            if (foi.getName() == null) {
                throw new IncompleteEntityException("name can not be null.");
            }
            update.set(qfoi.name, foi.getName());
            message.addField(EntityProperty.NAME);
        }
        if (foi.isSetDescription()) {
            if (foi.getDescription() == null) {
                throw new IncompleteEntityException("description can not be null.");
            }
            update.set(qfoi.description, foi.getDescription());
            message.addField(EntityProperty.DESCRIPTION);
        }
        if (foi.isSetProperties()) {
            update.set(qfoi.properties, objectToJson(foi.getProperties()));
            message.addField(EntityProperty.PROPERTIES);
        }

        if (foi.isSetEncodingType() && foi.getEncodingType() == null) {
            throw new IncompleteEntityException("encodingType can not be null.");
        }
        if (foi.isSetFeature() && foi.getFeature() == null) {
            throw new IncompleteEntityException("feature can not be null.");
        }
        if (foi.isSetEncodingType() && foi.getEncodingType() != null && foi.isSetFeature() && foi.getFeature() != null) {
            String encodingType = foi.getEncodingType();
            update.set(qfoi.encodingType, encodingType);
            insertGeometry(update, qfoi.feature, qfoi.geom, encodingType, foi.getFeature());
            message.addField(EntityProperty.ENCODINGTYPE);
            message.addField(EntityProperty.FEATURE);
        } else if (foi.isSetEncodingType() && foi.getEncodingType() != null) {
            String encodingType = foi.getEncodingType();
            update.set(qfoi.encodingType, encodingType);
            message.addField(EntityProperty.ENCODINGTYPE);
        } else if (foi.isSetFeature() && foi.getFeature() != null) {
            String encodingType = qFactory.select(qfoi.encodingType)
                    .from(qfoi)
                    .where(qfoi.id.eq(foiId))
                    .fetchFirst();
            Object parsedObject = reParseGeometry(encodingType, foi.getFeature());
            insertGeometry(update, qfoi.feature, qfoi.geom, encodingType, parsedObject);
            message.addField(EntityProperty.FEATURE);
        }

        update.where(qfoi.id.eq(foiId));
        long count = 0;
        if (!update.isEmpty()) {
            count = update.execute();
        }
        if (count > 1) {
            LOGGER.error("Updating FeatureOfInterest {} caused {} rows to change!", foiId, count);
            throw new IllegalStateException("Update changed multiple rows.");
        }

        // Link existing Observations to the FeatureOfInterest.
        for (Observation o : foi.getObservations()) {
            if (o.getId() == null || !entityExists(o)) {
                throw new NoSuchEntityException("Observation with no id or non existing.");
            }
            Long obsId = (Long) o.getId().getValue();
            QObservations qo = QObservations.observations;
            long oCount = qFactory.update(qo)
                    .set(qo.featureId, foiId)
                    .where(qo.id.eq(obsId))
                    .execute();
            if (oCount > 0) {
                LOGGER.debug("Assigned FeatureOfInterest {} to Observation {}.", foiId, obsId);
            }
        }

        LOGGER.debug("Updated FeatureOfInterest {}", foiId);
        return message;
    }

    public FeatureOfInterest generateFeatureOfInterest(Id datastreamId, boolean isMultiDatastream) throws NoSuchEntityException {
        Long dsId = (Long) datastreamId.getValue();
        SQLQueryFactory qf = pm.createQueryFactory();
        QLocations ql = QLocations.locations;
        QThingsLocations qtl = QThingsLocations.thingsLocations;
        QThings qt = QThings.things;
        QDatastreams qd = QDatastreams.datastreams;
        QMultiDatastreams qmd = QMultiDatastreams.multiDatastreams;

        SQLQuery<Tuple> query = qf.select(ql.id, ql.genFoiId, ql.encodingType)
                .from(ql)
                .innerJoin(qtl).on(ql.id.eq(qtl.locationId))
                .innerJoin(qt).on(qt.id.eq(qtl.thingId));
        if (isMultiDatastream) {
            query.innerJoin(qmd).on(qmd.thingId.eq(qt.id))
                    .where(qmd.id.eq(dsId));
        } else {
            query.innerJoin(qd).on(qd.thingId.eq(qt.id))
                    .where(qd.id.eq(dsId));
        }
        List<Tuple> tuples = query.fetch();
        if (tuples.isEmpty()) {
            // Can not generate foi from Thing with no locations.
            throw new NoSuchEntityException("Can not generate foi for Thing with no locations.");
        }
        // See if any of the locations have a generated foi.
        // Also track if any of the location has a supported encoding type.
        Long genFoiId = null;
        Long locationId = null;
        for (Tuple tuple : tuples) {
            genFoiId = tuple.get(ql.genFoiId);
            if (genFoiId != null) {
                break;
            }
            String encodingType = tuple.get(ql.encodingType);
            if (encodingType != null && GeoJsonDeserializier.ENCODINGS.contains(encodingType.toLowerCase())) {
                locationId = tuple.get(ql.id);
            }
        }
        // Either genFoiId will have a value, if a generated foi was found,
        // Or locationId will have a value if a supported encoding type was found.

        FeatureOfInterest foi;
        if (genFoiId != null) {
            foi = new FeatureOfInterest();
            foi.setId(new IdLong(genFoiId));
        } else if (locationId != null) {
            query = qf.select(ql.id, ql.encodingType, ql.location)
                    .from(ql)
                    .where(ql.id.eq(locationId));
            Tuple tuple = query.fetchOne();
            if (tuple == null) {
                // Can not generate foi from Thing with no locations.
                // Should not happen, since the query succeeded just before.
                throw new NoSuchEntityException("Can not generate foi for Thing with no locations.");
            }
            String encoding = tuple.get(ql.encodingType);
            String locString = tuple.get(ql.location);
            Object locObject = PropertyHelper.locationFromEncoding(encoding, locString);
            foi = new FeatureOfInterestBuilder()
                    .setName("FoI for location " + locationId)
                    .setDescription("Generated from location " + locationId)
                    .setEncodingType(encoding)
                    .setFeature(locObject)
                    .build();
            insertFeatureOfInterest(foi);
            Long foiId = (Long) foi.getId().getValue();
            qf.update(ql)
                    .set(ql.genFoiId, (Long) foi.getId().getValue())
                    .where(ql.id.eq(locationId))
                    .execute();
            LOGGER.debug("Generated foi {} from Location {}.", foiId, locationId);
        } else {
            // Can not generate foi from Thing with no locations.
            throw new NoSuchEntityException("Can not generate foi for Thing, all locations have an un supported encoding type.");
        }
        return foi;
    }

    public boolean insertHistoricalLocation(HistoricalLocation h) throws NoSuchEntityException, IncompleteEntityException {
        Thing t = h.getThing();
        entityExistsOrCreate(t);
        Long thingId = (Long) h.getThing().getId().getValue();

        Timestamp newTime = new Timestamp(h.getTime().getDateTime().getMillis());

        SQLQueryFactory qFactory = pm.createQueryFactory();
        QHistLocations qhl = QHistLocations.histLocations;
        SQLInsertClause insert = qFactory.insert(qhl);
        insert.set(qhl.time, newTime);
        insert.set(qhl.thingId, thingId);

        insertUserDefinedId(insert, qhl.id, h);

        Long generatedId = insert.executeWithKey(qhl.id);
        LOGGER.debug("Inserted HistoricalLocation. Created id = {}.", generatedId);
        h.setId(new IdLong(generatedId));

        EntitySet<Location> locations = h.getLocations();
        for (Location l : locations) {
            entityExistsOrCreate(l);
            Long lId = (Long) l.getId().getValue();
            QLocationsHistLocations qlhl = QLocationsHistLocations.locationsHistLocations;
            insert = qFactory.insert(qlhl);
            insert.set(qlhl.histLocationId, generatedId);
            insert.set(qlhl.locationId, lId);
            insert.execute();
            LOGGER.debug("Linked Location {} to HistoricalLocation {}.", lId, generatedId);
        }

        // https://github.com/opengeospatial/sensorthings/issues/30
        // Check the time of the latest HistoricalLocation of our thing.
        // If this time is earlier than our time, set the Locations of our Thing to our Locations.
        Tuple lastHistLocation = qFactory.select(qhl.all())
                .from(qhl)
                .where(qhl.thingId.eq(thingId).and(qhl.time.gt(newTime)))
                .orderBy(qhl.time.desc())
                .limit(1).fetchFirst();
        if (lastHistLocation == null) {
            // We are the newest.
            // Unlink old Locations from Thing.
            QThingsLocations qtl = QThingsLocations.thingsLocations;
            long count = qFactory.delete(qtl).where(qtl.thingId.eq(thingId)).execute();
            LOGGER.debug("Unlinked {} locations from Thing {}.", count, thingId);

            // Link new locations to Thing, track the ids.
            for (Location l : h.getLocations()) {
                if (l.getId() == null || !entityExists(l)) {
                    throw new NoSuchEntityException("Location with no id.");
                }
                Long locationId = (Long) l.getId().getValue();

                qFactory.insert(qtl)
                        .set(qtl.thingId, thingId)
                        .set(qtl.locationId, locationId)
                        .execute();
                LOGGER.debug("Linked Location {} to Thing {}.", locationId, thingId);
            }
        }
        return true;
    }

    public EntityChangedMessage updateHistoricalLocation(HistoricalLocation hl, long id) {
        SQLQueryFactory qFactory = pm.createQueryFactory();
        QHistLocations qhl = QHistLocations.histLocations;
        SQLUpdateClause update = qFactory.update(qhl);
        EntityChangedMessage message = new EntityChangedMessage();

        if (hl.isSetThing()) {
            if (!entityExists(hl.getThing())) {
                throw new IncompleteEntityException("Thing can not be null.");
            }
            update.set(qhl.thingId, (Long) hl.getThing().getId().getValue());
            message.addField(NavigationProperty.THING);
        }
        if (hl.isSetTime()) {
            if (hl.getTime() == null) {
                throw new IncompleteEntityException("time can not be null.");
            }
            insertTimeInstant(update, qhl.time, hl.getTime());
            message.addField(EntityProperty.TIME);
        }
        update.where(qhl.id.eq(id));
        long count = 0;
        if (!update.isEmpty()) {
            count = update.execute();
        }
        if (count > 1) {
            LOGGER.error("Updating Location {} caused {} rows to change!", id, count);
            throw new IllegalStateException("Update changed multiple rows.");
        }
        LOGGER.debug("Updated Location {}", id);

        // Link existing locations to the HistoricalLocation.
        for (Location l : hl.getLocations()) {
            if (!entityExists(l)) {
                throw new IllegalArgumentException("Unknown Location or Location with no id.");
            }
            Long lId = (Long) l.getId().getValue();

            QLocationsHistLocations qlhl = QLocationsHistLocations.locationsHistLocations;
            SQLInsertClause insert = qFactory.insert(qlhl);
            insert.set(qlhl.histLocationId, id);
            insert.set(qlhl.locationId, lId);
            insert.execute();
            LOGGER.debug("Linked Location {} to HistoricalLocation {}.", lId, id);
        }
        return message;
    }

    public boolean insertLocation(Location l) throws NoSuchEntityException, IncompleteEntityException {
        SQLQueryFactory qFactory = pm.createQueryFactory();
        QLocations ql = QLocations.locations;
        SQLInsertClause insert = qFactory.insert(ql);
        insert.set(ql.name, l.getName());
        insert.set(ql.description, l.getDescription());
        insert.set(ql.properties, objectToJson(l.getProperties()));

        String encodingType = l.getEncodingType();
        insert.set(ql.encodingType, encodingType);
        insertGeometry(insert, ql.location, ql.geom, encodingType, l.getLocation());

        insertUserDefinedId(insert, ql.id, l);

        Long locationId = insert.executeWithKey(ql.id);
        LOGGER.debug("Inserted Location. Created id = {}.", locationId);
        l.setId(new IdLong(locationId));

        // Link Things
        EntitySet<Thing> things = l.getThings();
        for (Thing t : things) {
            entityExistsOrCreate(t);
            Long thingId = (Long) t.getId().getValue();

            // Unlink old Locations from Thing.
            QThingsLocations qtl = QThingsLocations.thingsLocations;
            long count = qFactory.delete(qtl).where(qtl.thingId.eq(thingId)).execute();
            LOGGER.debug("Unlinked {} locations from Thing {}.", count, thingId);

            // Link new Location to thing.
            insert = qFactory.insert(qtl);
            insert.set(qtl.thingId, thingId);
            insert.set(qtl.locationId, locationId);
            insert.execute();
            LOGGER.debug("Linked Location {} to Thing {}.", locationId, thingId);

            // Create HistoricalLocation for Thing
            QHistLocations qhl = QHistLocations.histLocations;
            insert = qFactory.insert(qhl);
            insert.set(qhl.thingId, thingId);
            insert.set(qhl.time, new Timestamp(Calendar.getInstance().getTimeInMillis()));
            // TODO: maybe use histLocationId based on locationId
            Long histLocationId = insert.executeWithKey(qhl.id);
            LOGGER.debug("Created historicalLocation {}", histLocationId);

            // Link Location to HistoricalLocation.
            QLocationsHistLocations qlhl = QLocationsHistLocations.locationsHistLocations;
            qFactory.insert(qlhl)
                    .set(qlhl.histLocationId, histLocationId)
                    .set(qlhl.locationId, locationId)
                    .execute();
            LOGGER.debug("Linked location {} to historicalLocation {}.", locationId, histLocationId);
        }

        return true;
    }

    public EntityChangedMessage updateLocation(Location l, long locationId) throws NoSuchEntityException {
        SQLQueryFactory qFactory = pm.createQueryFactory();
        QLocations ql = QLocations.locations;
        SQLUpdateClause update = qFactory.update(ql);
        EntityChangedMessage message = new EntityChangedMessage();

        if (l.isSetName()) {
            if (l.getName() == null) {
                throw new IncompleteEntityException("name can not be null.");
            }
            update.set(ql.name, l.getName());
            message.addField(EntityProperty.NAME);
        }
        if (l.isSetDescription()) {
            if (l.getDescription() == null) {
                throw new IncompleteEntityException("description can not be null.");
            }
            update.set(ql.description, l.getDescription());
            message.addField(EntityProperty.DESCRIPTION);
        }
        if (l.isSetProperties()) {
            update.set(ql.properties, objectToJson(l.getProperties()));
            message.addField(EntityProperty.PROPERTIES);
        }

        if (l.isSetEncodingType() && l.getEncodingType() == null) {
            throw new IncompleteEntityException("encodingType can not be null.");
        }
        if (l.isSetLocation() && l.getLocation() == null) {
            throw new IncompleteEntityException("locations can not be null.");
        }
        if (l.isSetEncodingType() && l.getEncodingType() != null && l.isSetLocation() && l.getLocation() != null) {
            String encodingType = l.getEncodingType();
            update.set(ql.encodingType, encodingType);
            insertGeometry(update, ql.location, ql.geom, encodingType, l.getLocation());
            message.addField(EntityProperty.ENCODINGTYPE);
            message.addField(EntityProperty.LOCATION);
        } else if (l.isSetEncodingType() && l.getEncodingType() != null) {
            String encodingType = l.getEncodingType();
            update.set(ql.encodingType, encodingType);
            message.addField(EntityProperty.ENCODINGTYPE);
        } else if (l.isSetLocation() && l.getLocation() != null) {
            String encodingType = qFactory.select(ql.encodingType)
                    .from(ql)
                    .where(ql.id.eq(locationId))
                    .fetchFirst();
            Object parsedObject = reParseGeometry(encodingType, l.getLocation());
            insertGeometry(update, ql.location, ql.geom, encodingType, parsedObject);
            message.addField(EntityProperty.LOCATION);
        }

        update.where(ql.id.eq(locationId));
        long count = 0;
        if (!update.isEmpty()) {
            count = update.execute();
        }
        if (count > 1) {
            LOGGER.error("Updating Location {} caused {} rows to change!", locationId, count);
            throw new IllegalStateException("Update changed multiple rows.");
        }
        LOGGER.debug("Updated Location {}", locationId);

        // Link HistoricalLocation.
        for (HistoricalLocation hl : l.getHistoricalLocations()) {
            if (hl.getId() == null) {
                throw new IllegalArgumentException("HistoricalLocation with no id.");
            }
            Long hlId = (Long) hl.getId().getValue();

            QLocationsHistLocations qlhl = QLocationsHistLocations.locationsHistLocations;
            SQLInsertClause insert = qFactory.insert(qlhl);
            insert.set(qlhl.histLocationId, hlId);
            insert.set(qlhl.locationId, locationId);
            insert.execute();
            LOGGER.debug("Linked Location {} to HistoricalLocation {}.", locationId, hlId);
        }

        // Link Things
        EntitySet<Thing> things = l.getThings();
        for (Thing t : things) {
            if (!entityExists(t)) {
                throw new NoSuchEntityException("Thing not found.");
            }
            Long thingId = (Long) t.getId().getValue();

            // Unlink old Locations from Thing.
            QThingsLocations qtl = QThingsLocations.thingsLocations;
            count = qFactory.delete(qtl).where(qtl.thingId.eq(thingId)).execute();
            LOGGER.debug("Unlinked {} locations from Thing {}.", count, thingId);

            // Link new Location to thing.
            SQLInsertClause insert = qFactory.insert(qtl);
            insert.set(qtl.thingId, thingId);
            insert.set(qtl.locationId, locationId);
            insert.execute();
            LOGGER.debug("Linked Location {} to Thing {}.", locationId, thingId);

            // Create HistoricalLocation for Thing
            QHistLocations qhl = QHistLocations.histLocations;
            insert = qFactory.insert(qhl);
            insert.set(qhl.thingId, thingId);
            insert.set(qhl.time, new Timestamp(Calendar.getInstance().getTimeInMillis()));
            // TODO: maybe use histLocationId based on locationId
            Long histLocationId = insert.executeWithKey(qhl.id);
            LOGGER.debug("Created historicalLocation {}", histLocationId);

            // Link Location to HistoricalLocation.
            QLocationsHistLocations qlhl = QLocationsHistLocations.locationsHistLocations;
            qFactory.insert(qlhl)
                    .set(qlhl.histLocationId, histLocationId)
                    .set(qlhl.locationId, locationId)
                    .execute();
            LOGGER.debug("Linked location {} to historicalLocation {}.", locationId, histLocationId);
        }
        return message;
    }

    public boolean insertObservation(Observation o) throws NoSuchEntityException, IncompleteEntityException {
        Datastream ds = o.getDatastream();
        MultiDatastream mds = o.getMultiDatastream();
        Id streamId;
        boolean isMultiDatastream = false;
        if (ds != null) {
            entityExistsOrCreate(ds);
            streamId = ds.getId();
        } else if (mds != null) {
            entityExistsOrCreate(mds);
            streamId = mds.getId();
            isMultiDatastream = true;
        } else {
            throw new IncompleteEntityException("Missing Datastream or MultiDatastream.");
        }

        FeatureOfInterest f = o.getFeatureOfInterest();
        if (f == null) {
            f = generateFeatureOfInterest(streamId, isMultiDatastream);
        } else {
            entityExistsOrCreate(f);
        }

        SQLQueryFactory qFactory = pm.createQueryFactory();
        QObservations qo = QObservations.observations;
        SQLInsertClause insert = qFactory.insert(qo);

        insert.set(qo.parameters, objectToJson(o.getParameters()));
        TimeValue phenomenonTime = o.getPhenomenonTime();
        if (phenomenonTime == null) {
            phenomenonTime = TimeInstant.now();
        }
        insertTimeValue(insert, qo.phenomenonTimeStart, qo.phenomenonTimeEnd, phenomenonTime);
        insertTimeInstant(insert, qo.resultTime, o.getResultTime());
        insertTimeInterval(insert, qo.validTimeStart, qo.validTimeEnd, o.getValidTime());

        Object result = o.getResult();
        if (isMultiDatastream) {
            if (!(result instanceof List)) {
                throw new IllegalArgumentException("Multidatastream only accepts array results.");
            }
            List list = (List) result;
            ResourcePath path = mds.getPath();
            path.addPathElement(new EntitySetPathElement(EntityType.OBSERVEDPROPERTY, null), false, false);
            long count = pm.count(path, null);
            if (count != list.size()) {
                throw new IllegalArgumentException("Size of result array (" + list.size() + ") must match number of observed properties (" + count + ") in the MultiDatastream.");
            }
        }

        if (result instanceof Number) {
            insert.set(qo.resultType, ResultType.NUMBER.sqlValue());
            insert.set(qo.resultString, result.toString());
            insert.set(qo.resultNumber, ((Number) result).doubleValue());
        } else if (result instanceof Boolean) {
            insert.set(qo.resultType, ResultType.BOOLEAN.sqlValue());
            insert.set(qo.resultString, result.toString());
            insert.set(qo.resultBoolean, (Boolean) result);
        } else if (result instanceof String) {
            insert.set(qo.resultType, ResultType.STRING.sqlValue());
            insert.set(qo.resultString, result.toString());
        } else {
            insert.set(qo.resultType, ResultType.OBJECT_ARRAY.sqlValue());
            insert.set(qo.resultJson, objectToJson(result));
        }

        if (o.getResultQuality() != null) {
            insert.set(qo.resultQuality, o.getResultQuality().toString());
        }
        if (ds != null) {
            insert.set(qo.datastreamId, (Long) ds.getId().getValue());
        }
        if (mds != null) {
            insert.set(qo.multiDatastreamId, (Long) mds.getId().getValue());
        }
        insert.set(qo.featureId, (Long) f.getId().getValue());

        insertUserDefinedId(insert, qo.id, o);

        Long generatedId = insert.executeWithKey(qo.id);
        LOGGER.debug("Inserted Observation. Created id = {}.", generatedId);
        o.setId(new IdLong(generatedId));
        return true;
    }

    public EntityChangedMessage updateObservation(Observation o, long id) {
        Observation oldObservation = (Observation) pm.get(EntityType.OBSERVATION, new IdLong(id));
        Datastream ds = oldObservation.getDatastream();
        MultiDatastream mds = oldObservation.getMultiDatastream();
        boolean newHasDatastream = ds != null;
        boolean newHasMultiDatastream = mds != null;

        SQLQueryFactory qFactory = pm.createQueryFactory();
        QObservations qo = QObservations.observations;
        SQLUpdateClause update = qFactory.update(qo);
        EntityChangedMessage message = new EntityChangedMessage();

        if (o.isSetDatastream()) {
            if (o.getDatastream() == null) {
                newHasDatastream = false;
                update.setNull(qo.datastreamId);
                message.addField(NavigationProperty.DATASTREAM);
            } else {
                if (!entityExists(o.getDatastream())) {
                    throw new IncompleteEntityException("Datastream not found.");
                }
                newHasDatastream = true;
                ds = o.getDatastream();
                update.set(qo.datastreamId, (Long) ds.getId().getValue());
                message.addField(NavigationProperty.DATASTREAM);
            }
        }
        if (o.isSetMultiDatastream()) {
            mds = o.getMultiDatastream();
            if (mds == null) {
                newHasMultiDatastream = false;
                update.setNull(qo.multiDatastreamId);
                message.addField(NavigationProperty.MULTIDATASTREAM);
            } else {
                if (!entityExists(mds)) {
                    throw new IncompleteEntityException("MultiDatastream not found.");
                }
                newHasMultiDatastream = true;
                update.set(qo.multiDatastreamId, (Long) mds.getId().getValue());
                message.addField(NavigationProperty.MULTIDATASTREAM);
            }
        }
        if (newHasDatastream == newHasMultiDatastream) {
            throw new IllegalArgumentException("Observation must have either a Datastream or a MultiDatastream.");
        }
        if (o.isSetFeatureOfInterest()) {
            if (!entityExists(o.getFeatureOfInterest())) {
                throw new IncompleteEntityException("FeatureOfInterest not found.");
            }
            update.set(qo.featureId, (Long) o.getFeatureOfInterest().getId().getValue());
            message.addField(NavigationProperty.FEATUREOFINTEREST);
        }
        if (o.isSetParameters()) {
            update.set(qo.parameters, objectToJson(o.getParameters()));
            message.addField(EntityProperty.PARAMETERS);
        }
        if (o.isSetPhenomenonTime()) {
            if (o.getPhenomenonTime() == null) {
                throw new IncompleteEntityException("phenomenonTime can not be null.");
            }
            insertTimeValue(update, qo.phenomenonTimeStart, qo.phenomenonTimeEnd, o.getPhenomenonTime());
            message.addField(EntityProperty.PHENOMENONTIME);
        }

        if (o.isSetResult() && o.getResult() != null) {
            Object result = o.getResult();
            if (newHasMultiDatastream) {
                if (!(result instanceof List)) {
                    throw new IllegalArgumentException("Multidatastream only accepts array results.");
                }
                List list = (List) result;
                ResourcePath path = mds.getPath();
                path.addPathElement(new EntitySetPathElement(EntityType.OBSERVEDPROPERTY, null), false, false);
                long count = pm.count(path, null);
                if (count != list.size()) {
                    throw new IllegalArgumentException("Size of result array (" + list.size() + ") must match number of observed properties (" + count + ") in the MultiDatastream.");
                }
            }
            if (result instanceof Number) {
                update.set(qo.resultType, ResultType.NUMBER.sqlValue());
                update.set(qo.resultString, result.toString());
                update.set(qo.resultNumber, ((Number) result).doubleValue());
                update.setNull(qo.resultBoolean);
                update.setNull(qo.resultJson);
            } else if (result instanceof Boolean) {
                update.set(qo.resultType, ResultType.BOOLEAN.sqlValue());
                update.set(qo.resultString, result.toString());
                update.set(qo.resultBoolean, (Boolean) result);
                update.setNull(qo.resultNumber);
                update.setNull(qo.resultJson);
            } else if (result instanceof String) {
                update.set(qo.resultType, ResultType.STRING.sqlValue());
                update.set(qo.resultString, result.toString());
                update.setNull(qo.resultNumber);
                update.setNull(qo.resultBoolean);
                update.setNull(qo.resultJson);
            } else {
                update.set(qo.resultType, ResultType.OBJECT_ARRAY.sqlValue());
                update.set(qo.resultJson, objectToJson(result));
                update.setNull(qo.resultString);
                update.setNull(qo.resultNumber);
                update.setNull(qo.resultBoolean);
            }
            message.addField(EntityProperty.RESULT);
        }

        if (o.isSetResultQuality()) {
            update.set(qo.resultQuality, objectToJson(o.getResultQuality()));
            message.addField(EntityProperty.RESULTQUALITY);
        }
        if (o.isSetResultTime()) {
            insertTimeInstant(update, qo.resultTime, o.getResultTime());
            message.addField(EntityProperty.RESULTTIME);
        }
        if (o.isSetValidTime()) {
            insertTimeInterval(update, qo.validTimeStart, qo.validTimeEnd, o.getValidTime());
            message.addField(EntityProperty.VALIDTIME);
        }
        update.where(qo.id.eq(id));
        long count = 0;
        if (!update.isEmpty()) {
            count = update.execute();
        }
        if (count > 1) {
            LOGGER.error("Updating Observation {} caused {} rows to change!", id, count);
            throw new IllegalStateException("Update changed multiple rows.");
        }
        LOGGER.debug("Updated Observation {}", id);
        return message;
    }

    public boolean insertObservedProperty(ObservedProperty op) throws NoSuchEntityException, IncompleteEntityException {
        SQLQueryFactory qFactory = pm.createQueryFactory();
        QObsProperties qop = QObsProperties.obsProperties;
        SQLInsertClause insert = qFactory.insert(qop);
        insert.set(qop.definition, op.getDefinition());
        insert.set(qop.name, op.getName());
        insert.set(qop.description, op.getDescription());
        insert.set(qop.properties, objectToJson(op.getProperties()));

        insertUserDefinedId(insert, qop.id, op);

        Long generatedId = insert.executeWithKey(qop.id);
        LOGGER.debug("Inserted ObservedProperty. Created id = {}.", generatedId);
        op.setId(new IdLong(generatedId));

        // Create new datastreams, if any.
        for (Datastream ds : op.getDatastreams()) {
            ds.setSensor(new SensorBuilder().setId(op.getId()).build());
            ds.complete();
            pm.insert(ds);
        }

        // Create new multiDatastreams, if any.
        for (MultiDatastream mds : op.getMultiDatastreams()) {
            mds.setSensor(new SensorBuilder().setId(op.getId()).build());
            mds.complete();
            pm.insert(mds);
        }

        return true;
    }

    public EntityChangedMessage updateObservedProperty(ObservedProperty op, long opId) throws NoSuchEntityException {
        SQLQueryFactory qFactory = pm.createQueryFactory();
        QObsProperties qop = QObsProperties.obsProperties;
        SQLUpdateClause update = qFactory.update(qop);
        EntityChangedMessage message = new EntityChangedMessage();

        if (op.isSetDefinition()) {
            if (op.getDefinition() == null) {
                throw new IncompleteEntityException("definition can not be null.");
            }
            update.set(qop.definition, op.getDefinition());
            message.addField(EntityProperty.DEFINITION);
        }
        if (op.isSetDescription()) {
            if (op.getDescription() == null) {
                throw new IncompleteEntityException("description can not be null.");
            }
            update.set(qop.description, op.getDescription());
            message.addField(EntityProperty.DESCRIPTION);
        }
        if (op.isSetName()) {
            if (op.getName() == null) {
                throw new IncompleteEntityException("name can not be null.");
            }
            update.set(qop.name, op.getName());
            message.addField(EntityProperty.NAME);
        }
        if (op.isSetProperties()) {
            update.set(qop.properties, objectToJson(op.getProperties()));
            message.addField(EntityProperty.PROPERTIES);
        }

        update.where(qop.id.eq(opId));
        long count = 0;
        if (!update.isEmpty()) {
            count = update.execute();
        }
        if (count > 1) {
            LOGGER.error("Updating ObservedProperty {} caused {} rows to change!", opId, count);
            throw new IllegalStateException("Update changed multiple rows.");
        }

        // Link existing Datastreams to the observedProperty.
        for (Datastream ds : op.getDatastreams()) {
            if (ds.getId() == null || !entityExists(ds)) {
                throw new NoSuchEntityException("ObservedProperty with no id or non existing.");
            }
            Long dsId = (Long) ds.getId().getValue();
            QDatastreams qds = QDatastreams.datastreams;
            long dsCount = qFactory.update(qds)
                    .set(qds.obsPropertyId, opId)
                    .where(qds.id.eq(dsId))
                    .execute();
            if (dsCount > 0) {
                LOGGER.debug("Assigned datastream {} to ObservedProperty {}.", dsId, opId);
            }
        }

        if (!op.getMultiDatastreams().isEmpty()) {
            throw new IllegalArgumentException("Can not add MultiDatastreams to an ObservedProperty.");
        }

        LOGGER.debug("Updated ObservedProperty {}", opId);
        return message;
    }

    public boolean insertSensor(Sensor s) throws NoSuchEntityException, IncompleteEntityException {
        SQLQueryFactory qFactory = pm.createQueryFactory();
        QSensors qs = QSensors.sensors;
        SQLInsertClause insert = qFactory.insert(qs);
        insert.set(qs.name, s.getName());
        insert.set(qs.description, s.getDescription());
        insert.set(qs.encodingType, s.getEncodingType());
        // TODO: Check metadata serialisation.
        insert.set(qs.metadata, s.getMetadata().toString());
        insert.set(qs.properties, objectToJson(s.getProperties()));

        insertUserDefinedId(insert, qs.id, s);

        Long generatedId = insert.executeWithKey(qs.id);
        LOGGER.debug("Inserted Sensor. Created id = {}.", generatedId);
        s.setId(new IdLong(generatedId));

        // Create new datastreams, if any.
        for (Datastream ds : s.getDatastreams()) {
            ds.setSensor(new SensorBuilder().setId(s.getId()).build());
            ds.complete();
            pm.insert(ds);
        }

        // Create new multiDatastreams, if any.
        for (MultiDatastream mds : s.getMultiDatastreams()) {
            mds.setSensor(new SensorBuilder().setId(s.getId()).build());
            mds.complete();
            pm.insert(mds);
        }

        return true;
    }

    public EntityChangedMessage updateSensor(Sensor s, long sensorId) throws NoSuchEntityException {
        SQLQueryFactory qFactory = pm.createQueryFactory();
        QSensors qs = QSensors.sensors;
        SQLUpdateClause update = qFactory.update(qs);
        EntityChangedMessage message = new EntityChangedMessage();

        if (s.isSetName()) {
            if (s.getName() == null) {
                throw new IncompleteEntityException("name can not be null.");
            }
            update.set(qs.name, s.getName());
            message.addField(EntityProperty.NAME);
        }
        if (s.isSetDescription()) {
            if (s.getDescription() == null) {
                throw new IncompleteEntityException("description can not be null.");
            }
            update.set(qs.description, s.getDescription());
            message.addField(EntityProperty.DESCRIPTION);
        }
        if (s.isSetEncodingType()) {
            if (s.getEncodingType() == null) {
                throw new IncompleteEntityException("encodingType can not be null.");
            }
            update.set(qs.encodingType, s.getEncodingType());
            message.addField(EntityProperty.ENCODINGTYPE);
        }
        if (s.isSetMetadata()) {
            if (s.getMetadata() == null) {
                throw new IncompleteEntityException("metadata can not be null.");
            }
            // TODO: Check metadata serialisation.
            update.set(qs.metadata, s.getMetadata().toString());
            message.addField(EntityProperty.METADATA);
        }
        if (s.isSetProperties()) {
            update.set(qs.properties, objectToJson(s.getProperties()));
            message.addField(EntityProperty.PROPERTIES);
        }

        update.where(qs.id.eq(sensorId));
        long count = 0;
        if (!update.isEmpty()) {
            count = update.execute();
        }
        if (count > 1) {
            LOGGER.error("Updating Sensor {} caused {} rows to change!", sensorId, count);
            throw new IllegalStateException("Update changed multiple rows.");
        }

        // Link existing Datastreams to the sensor.
        for (Datastream ds : s.getDatastreams()) {
            if (ds.getId() == null || !entityExists(ds)) {
                throw new NoSuchEntityException("Datastream with no id or non existing.");
            }
            Long dsId = (Long) ds.getId().getValue();
            QDatastreams qds = QDatastreams.datastreams;
            long dsCount = qFactory.update(qds)
                    .set(qds.sensorId, sensorId)
                    .where(qds.id.eq(dsId))
                    .execute();
            if (dsCount > 0) {
                LOGGER.debug("Assigned datastream {} to sensor {}.", dsId, sensorId);
            }
        }

        // Link existing MultiDatastreams to the sensor.
        for (MultiDatastream mds : s.getMultiDatastreams()) {
            if (mds.getId() == null || !entityExists(mds)) {
                throw new NoSuchEntityException("MultiDatastream with no id or non existing.");
            }
            Long mdsId = (Long) mds.getId().getValue();
            QMultiDatastreams qmds = QMultiDatastreams.multiDatastreams;
            long mdsCount = qFactory.update(qmds)
                    .set(qmds.sensorId, sensorId)
                    .where(qmds.id.eq(mdsId))
                    .execute();
            if (mdsCount > 0) {
                LOGGER.debug("Assigned multiDatastream {} to sensor {}.", mdsId, sensorId);
            }
        }

        LOGGER.debug("Updated Sensor {}", sensorId);
        return message;
    }

    public boolean insertThing(Thing t) throws NoSuchEntityException, IncompleteEntityException {
        SQLQueryFactory qFactory = pm.createQueryFactory();
        QThings qt = QThings.things;
        SQLInsertClause insert = qFactory.insert(qt);
        insert.set(qt.name, t.getName());
        insert.set(qt.description, t.getDescription());
        insert.set(qt.properties, objectToJson(t.getProperties()));

        insertUserDefinedId(insert, qt.id, t);

        Long thingId = insert.executeWithKey(qt.id);
        LOGGER.debug("Inserted Thing. Created id = {}.", thingId);
        t.setId(new IdLong(thingId));

        // Create new Locations, if any.
        List<Long> locationIds = new ArrayList<>();
        for (Location l : t.getLocations()) {
            entityExistsOrCreate(l);
            Long lId = (Long) l.getId().getValue();

            QThingsLocations qtl = QThingsLocations.thingsLocations;
            insert = qFactory.insert(qtl);
            insert.set(qtl.thingId, thingId);
            insert.set(qtl.locationId, lId);
            insert.execute();
            LOGGER.debug("Linked Location {} to Thing {}.", lId, thingId);
            locationIds.add(lId);
        }

        // Now link the new locations also to a historicalLocation.
        if (!locationIds.isEmpty()) {
            QHistLocations qhl = QHistLocations.histLocations;
            insert = qFactory.insert(qhl);
            insert.set(qhl.thingId, thingId);
            insert.set(qhl.time, new Timestamp(Calendar.getInstance().getTimeInMillis()));
            // TODO: maybe use histLocationId based on locationIds
            Long histLocationId = insert.executeWithKey(qhl.id);
            LOGGER.debug("Created historicalLocation {}", histLocationId);

            QLocationsHistLocations qlhl = QLocationsHistLocations.locationsHistLocations;
            for (Long locId : locationIds) {
                qFactory.insert(qlhl)
                        .set(qlhl.histLocationId, histLocationId)
                        .set(qlhl.locationId, locId)
                        .execute();
                LOGGER.debug("Linked location {} to historicalLocation {}.", locId, histLocationId);
            }
        }

        // Create new datastreams, if any.
        for (Datastream ds : t.getDatastreams()) {
            ds.setThing(new ThingBuilder().setId(t.getId()).build());
            ds.complete();
            pm.insert(ds);
        }

        // Create new multiDatastreams, if any.
        for (MultiDatastream mds : t.getMultiDatastreams()) {
            mds.setThing(new ThingBuilder().setId(t.getId()).build());
            mds.complete();
            pm.insert(mds);
        }

        // TODO: if we allow the creation of historicalLocations through Things
        // then we have to be able to link those to Locations we might have just created.
        // However, id juggling will be needed!
        return true;
    }

    public EntityChangedMessage updateThing(Thing t, long thingId) throws NoSuchEntityException {
        SQLQueryFactory qFactory = pm.createQueryFactory();
        QThings qt = QThings.things;
        SQLUpdateClause update = qFactory.update(qt);
        EntityChangedMessage message = new EntityChangedMessage();

        if (t.isSetName()) {
            if (t.getName() == null) {
                throw new IncompleteEntityException("name can not be null.");
            }
            update.set(qt.name, t.getName());
            message.addField(EntityProperty.NAME);
        }
        if (t.isSetDescription()) {
            if (t.getDescription() == null) {
                throw new IncompleteEntityException("description can not be null.");
            }
            update.set(qt.description, t.getDescription());
            message.addField(EntityProperty.DESCRIPTION);
        }
        if (t.isSetProperties()) {
            update.set(qt.properties, objectToJson(t.getProperties()));
            message.addField(EntityProperty.PROPERTIES);
        }
        update.where(qt.id.eq(thingId));
        long count = 0;
        if (!update.isEmpty()) {
            count = update.execute();
        }
        if (count > 1) {
            LOGGER.error("Updating Thing {} caused {} rows to change!", thingId, count);
            throw new IllegalStateException("Update changed multiple rows.");
        }
        LOGGER.debug("Updated Thing {}", thingId);

        // Link existing Datastreams to the thing.
        for (Datastream ds : t.getDatastreams()) {
            if (ds.getId() == null || !entityExists(ds)) {
                throw new NoSuchEntityException("Datastream with no id or non existing.");
            }
            Long dsId = (Long) ds.getId().getValue();
            QDatastreams qds = QDatastreams.datastreams;
            long dsCount = qFactory.update(qds)
                    .set(qds.thingId, thingId)
                    .where(qds.id.eq(dsId))
                    .execute();
            if (dsCount > 0) {
                LOGGER.debug("Assigned datastream {} to thing {}.", dsId, thingId);
            }
        }

        // Link existing MultiDatastreams to the thing.
        for (MultiDatastream mds : t.getMultiDatastreams()) {
            if (mds.getId() == null || !entityExists(mds)) {
                throw new NoSuchEntityException("MultiDatastream with no id or non existing.");
            }
            Long mdsId = (Long) mds.getId().getValue();
            QMultiDatastreams qmds = QMultiDatastreams.multiDatastreams;
            long mdsCount = qFactory.update(qmds)
                    .set(qmds.thingId, thingId)
                    .where(qmds.id.eq(mdsId))
                    .execute();
            if (mdsCount > 0) {
                LOGGER.debug("Assigned multiDatastream {} to thing {}.", mdsId, thingId);
            }
        }

        // Link existing locations to the thing.
        if (!t.getLocations().isEmpty()) {
            // Unlink old Locations from Thing.
            QThingsLocations qtl = QThingsLocations.thingsLocations;
            count = qFactory.delete(qtl).where(qtl.thingId.eq(thingId)).execute();
            LOGGER.debug("Unlinked {} locations from Thing {}.", count, thingId);

            // Link new locations to Thing, track the ids.
            List<Long> locationIds = new ArrayList<>();
            for (Location l : t.getLocations()) {
                if (l.getId() == null || !entityExists(l)) {
                    throw new NoSuchEntityException("Location with no id.");
                }
                Long locationId = (Long) l.getId().getValue();

                SQLInsertClause insert = qFactory.insert(qtl);
                insert.set(qtl.thingId, thingId);
                insert.set(qtl.locationId, locationId);
                insert.execute();
                LOGGER.debug("Linked Location {} to Thing {}.", locationId, thingId);
                locationIds.add(locationId);
            }

            // Now link the newly linked locations also to a historicalLocation.
            if (!locationIds.isEmpty()) {
                QHistLocations qhl = QHistLocations.histLocations;
                SQLInsertClause insert = qFactory.insert(qhl);
                insert.set(qhl.thingId, thingId);
                insert.set(qhl.time, new Timestamp(Calendar.getInstance().getTimeInMillis()));
                // TODO: maybe use histLocationId based on locationIds
                Long histLocationId = insert.executeWithKey(qhl.id);
                LOGGER.debug("Created historicalLocation {}", histLocationId);

                QLocationsHistLocations qlhl = QLocationsHistLocations.locationsHistLocations;
                for (Long locId : locationIds) {
                    qFactory.insert(qlhl)
                            .set(qlhl.histLocationId, histLocationId)
                            .set(qlhl.locationId, locId)
                            .execute();
                    LOGGER.debug("Linked location {} to historicalLocation {}.", locId, histLocationId);
                }
            }
        }
        return message;
    }

    private static <T extends StoreClause> void insertUserDefinedId(T clause, Path idPath, Entity entity) {
        IdGenerationHandlerLong idhandler = new IdGenerationHandlerLong(entity);
        if (idhandler.useClientSuppliedId()) {
            idhandler.modifyClientSuppliedId();
            clause.set(idPath, (Long) idhandler.getIdValue());
        }
    }

    private static <T extends StoreClause> T insertTimeValue(T clause, DateTimePath<Timestamp> startPath, DateTimePath<Timestamp> endPath, TimeValue time) {
        if (time instanceof TimeInstant) {
            TimeInstant timeInstant = (TimeInstant) time;
            insertTimeInstant(clause, endPath, timeInstant);
            return insertTimeInstant(clause, startPath, timeInstant);
        } else if (time instanceof TimeInterval) {
            TimeInterval timeInterval = (TimeInterval) time;
            return insertTimeInterval(clause, startPath, endPath, timeInterval);
        }
        return clause;
    }

    private static <T extends StoreClause> T insertTimeInstant(T clause, DateTimePath<Timestamp> path, TimeInstant time) {
        if (time == null) {
            return clause;
        }
        clause.set(path, new Timestamp(time.getDateTime().getMillis()));
        return clause;
    }

    private static <T extends StoreClause> T insertTimeInterval(T clause, DateTimePath<Timestamp> startPath, DateTimePath<Timestamp> endPath, TimeInterval time) {
        if (time == null) {
            return clause;
        }
        Interval interval = time.getInterval();
        clause.set(startPath, new Timestamp(interval.getStartMillis()));
        clause.set(endPath, new Timestamp(interval.getEndMillis()));
        return clause;
    }

    /**
     * Sets both the geometry and location in the clause.
     *
     * @param <T> The type of the clause.
     * @param clause The insert or update clause to add to.
     * @param locationPath The path to the location column.
     * @param geomPath The path to the geometry column.
     * @param encodingType The encoding type.
     * @param location The location.
     * @return The insert or update clause.
     */
    private <T extends StoreClause> T insertGeometry(T clause, StringPath locationPath, GeometryPath<Geometry> geomPath, String encodingType, final Object location) {
        if (encodingType != null && GeoJsonDeserializier.ENCODINGS.contains(encodingType.toLowerCase())) {
            String locJson;
            try {
                locJson = new GeoJsonSerializer().serialize(location);
            } catch (JsonProcessingException ex) {
                LOGGER.error("Failed to store.", ex);
                throw new IllegalArgumentException("encoding specifies geoJson, but location not parsable as such.");
            }

            // Postgres does not support Feature.
            Object geoLocation = location;
            if (location instanceof Feature) {
                geoLocation = ((Feature) location).getGeometry();
            }
            // Ensure the geoJson has a crs, otherwise Postgres complains.
            if (geoLocation instanceof GeoJsonObject) {
                GeoJsonObject geoJsonObject = (GeoJsonObject) geoLocation;
                Crs crs = geoJsonObject.getCrs();
                if (crs == null) {
                    crs = new Crs();
                    crs.setType(CrsType.name);
                    crs.getProperties().put("name", "EPSG:4326");
                    geoJsonObject.setCrs(crs);
                }
            }
            String geoJson;
            try {
                geoJson = new GeoJsonSerializer().serialize(geoLocation);
            } catch (JsonProcessingException ex) {
                LOGGER.error("Failed to store.", ex);
                throw new IllegalArgumentException("encoding specifies geoJson, but location not parsable as such.");
            }

            try {
                // geojson.jackson allows invalid polygons, geolatte catches those.
                new JsonMapper().fromJson(geoJson, Geometry.class);
            } catch (JsonException ex) {
                throw new IllegalArgumentException("Invalid geoJson: " + ex.getMessage());
            }
            clause.set(geomPath, Expressions.template(Geometry.class, "ST_Force2D(ST_Transform(ST_GeomFromGeoJSON({0}), 4326))", geoJson));
            clause.set(locationPath, locJson);
        } else {
            String json;
            json = objectToJson(location);
            clause.setNull(geomPath);
            clause.set(locationPath, json);
        }
        return clause;
    }

    private Object reParseGeometry(String encodingType, Object object) {
        String json = objectToJson(object);
        return PropertyHelper.locationFromEncoding(encodingType, json);
    }

    /**
     * Throws an exception if the entity has an id, but does not exist or if the
     * entity can not be created.
     *
     * @param pm the persistenceManager
     * @param e The Entity to check.
     * @throws NoSuchEntityException If the entity has an id, but does not
     * exist.
     * @throws IncompleteEntityException If the entity has no id, but is not
     * complete and can thus not be created.
     */
    private void entityExistsOrCreate(Entity e) throws NoSuchEntityException, IncompleteEntityException {
        if (e == null) {
            throw new NoSuchEntityException("No entity!");
        }

        if (e.getId() == null) {
            e.complete();
            // no id but complete -> create
            pm.insert(e);
            return;
        }

        if (entityExists(e)) {
            return;
        }

        // check if this is an incomplete entity
        try {
            e.complete();
        } catch (IncompleteEntityException exc) {
            // not complete and link entity does not exist
            throw new NoSuchEntityException("No such entity '" + e.getEntityType() + "' with id " + e.getId().getValue());
        }

        // complete with id -> create
        pm.insert(e);
        return;
    }

    public boolean entityExists(Entity e) {
        if (e == null || e.getId() == null) {
            return false;
        }
        long id = (long) e.getId().getValue();
        SQLQueryFactory qFactory = pm.createQueryFactory();
        long count = 0;
        switch (e.getEntityType()) {
            case DATASTREAM:
                QDatastreams d = QDatastreams.datastreams;
                count = qFactory.select()
                        .from(d)
                        .where(d.id.eq(id))
                        .fetchCount();
                break;

            case MULTIDATASTREAM:
                QMultiDatastreams md = QMultiDatastreams.multiDatastreams;
                count = qFactory.select()
                        .from(md)
                        .where(md.id.eq(id))
                        .fetchCount();
                break;

            case FEATUREOFINTEREST:
                QFeatures foi = QFeatures.features;
                count = qFactory.select()
                        .from(foi)
                        .where(foi.id.eq(id))
                        .fetchCount();
                break;

            case HISTORICALLOCATION:
                QHistLocations h = QHistLocations.histLocations;
                count = qFactory.select()
                        .from(h)
                        .where(h.id.eq(id))
                        .fetchCount();
                break;

            case LOCATION:
                QLocations l = QLocations.locations;
                count = qFactory.select()
                        .from(l)
                        .where(l.id.eq(id))
                        .fetchCount();
                break;

            case OBSERVATION:
                QObservations o = QObservations.observations;
                count = qFactory.select()
                        .from(o)
                        .where(o.id.eq(id))
                        .fetchCount();
                break;

            case OBSERVEDPROPERTY:
                QObsProperties op = QObsProperties.obsProperties;
                count = qFactory.select()
                        .from(op)
                        .where(op.id.eq(id))
                        .fetchCount();
                break;

            case SENSOR:
                QSensors s = QSensors.sensors;
                count = qFactory.select()
                        .from(s)
                        .where(s.id.eq(id))
                        .fetchCount();
                break;

            case THING:
                QThings t = QThings.things;
                count = qFactory.select()
                        .from(t)
                        .where(t.id.eq(id))
                        .fetchCount();
                break;

            default:
                throw new AssertionError(e.getEntityType().name());
        }
        if (count > 1) {
            LOGGER.error("More than one instance of {} with id {}.", e.getEntityType(), id);
        }
        return count > 0;
    }

    public boolean entityExists(ResourcePath path) {
        long count = pm.count(path, null);
        if (count > 1) {
            LOGGER.error("More than one instance of {}", path);
        }
        return count > 0;
    }

    public String objectToJson(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return getFormatter().writeValueAsString(object);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not serialise object.", ex);
        }
    }

    public ObjectMapper getFormatter() {
        if (formatter == null) {
            formatter = EntityParser.getSimpleObjectMapper();
        }
        return formatter;
    }

}
