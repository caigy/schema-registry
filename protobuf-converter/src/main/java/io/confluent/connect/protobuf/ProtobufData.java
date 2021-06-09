/*
 * Copyright 2020 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 *
 */

package io.confluent.connect.protobuf;

import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.Message;
import com.google.protobuf.StringValue;
import com.google.protobuf.util.Timestamps;
import io.confluent.protobuf.MetaProto;
import io.confluent.protobuf.MetaProto.Meta;
import io.confluent.protobuf.type.utils.DecimalUtils;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;
import org.apache.kafka.common.cache.Cache;
import org.apache.kafka.common.cache.LRUCache;
import org.apache.kafka.common.cache.SynchronizedCache;
import org.apache.kafka.connect.data.Date;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Time;
import org.apache.kafka.connect.data.Timestamp;
import org.apache.kafka.connect.errors.DataException;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema;
import io.confluent.kafka.schemaregistry.protobuf.dynamic.DynamicSchema;
import io.confluent.kafka.schemaregistry.protobuf.dynamic.EnumDefinition;
import io.confluent.kafka.schemaregistry.protobuf.dynamic.MessageDefinition;
import io.confluent.kafka.serializers.protobuf.ProtobufSchemaAndValue;

import static io.confluent.connect.protobuf.ProtobufDataConfig.SCHEMAS_CACHE_SIZE_CONFIG;
import static io.confluent.connect.protobuf.ProtobufDataConfig.SCHEMAS_CACHE_SIZE_DEFAULT;


public class ProtobufData {

  public static final String NAMESPACE = "io.confluent.connect.protobuf";

  public static final String DEFAULT_SCHEMA_NAME = "ConnectDefault";
  public static final String MAP_ENTRY_SUFFIX = ProtobufSchema.MAP_ENTRY_SUFFIX;  // Suffix used
  // by protoc
  public static final String KEY_FIELD = ProtobufSchema.KEY_FIELD;
  public static final String VALUE_FIELD = ProtobufSchema.VALUE_FIELD;

  public static final String PROTOBUF_TYPE_ENUM = NAMESPACE + ".Enum";
  public static final String PROTOBUF_TYPE_ENUM_PREFIX = PROTOBUF_TYPE_ENUM + ".";
  public static final String PROTOBUF_TYPE_UNION = NAMESPACE + ".Union";
  public static final String PROTOBUF_TYPE_UNION_PREFIX = PROTOBUF_TYPE_UNION + ".";
  public static final String PROTOBUF_TYPE_TAG = NAMESPACE + ".Tag";
  public static final String PROTOBUF_TYPE_PROP = NAMESPACE + ".Type";

  public static final String PROTOBUF_PRECISION_PROP = "precision";
  public static final String PROTOBUF_SCALE_PROP = "scale";
  public static final String PROTOBUF_DECIMAL_LOCATION = "confluent/type/decimal.proto";
  public static final String PROTOBUF_DECIMAL_TYPE = "confluent.type.Decimal";
  public static final String PROTOBUF_DATE_LOCATION = "google/type/date.proto";
  public static final String PROTOBUF_DATE_TYPE = "google.type.Date";
  public static final String PROTOBUF_TIME_LOCATION = "google/type/timeofday.proto";
  public static final String PROTOBUF_TIME_TYPE = "google.type.TimeOfDay";
  public static final String PROTOBUF_TIMESTAMP_LOCATION = "google/protobuf/timestamp.proto";
  public static final String PROTOBUF_TIMESTAMP_TYPE = "google.protobuf.Timestamp";

  public static final String PROTOBUF_WRAPPER_LOCATION = "google/protobuf/wrappers.proto";
  public static final String PROTOBUF_DOUBLE_WRAPPER_TYPE = "google.protobuf.DoubleValue";
  public static final String PROTOBUF_FLOAT_WRAPPER_TYPE = "google.protobuf.FloatValue";
  public static final String PROTOBUF_INT64_WRAPPER_TYPE = "google.protobuf.Int64Value";
  public static final String PROTOBUF_UINT64_WRAPPER_TYPE = "google.protobuf.UInt64Value";
  public static final String PROTOBUF_INT32_WRAPPER_TYPE = "google.protobuf.Int32Value";
  public static final String PROTOBUF_UINT32_WRAPPER_TYPE = "google.protobuf.UInt32Value";
  public static final String PROTOBUF_BOOL_WRAPPER_TYPE = "google.protobuf.BoolValue";
  public static final String PROTOBUF_STRING_WRAPPER_TYPE = "google.protobuf.StringValue";
  public static final String PROTOBUF_BYTES_WRAPPER_TYPE = "google.protobuf.BytesValue";

  public static final String CONNECT_PRECISION_PROP = "connect.decimal.precision";
  public static final String CONNECT_SCALE_PROP = Decimal.SCALE_FIELD;
  public static final String CONNECT_TYPE_PROP = "connect.type";
  public static final String CONNECT_TYPE_INT8 = "int8";
  public static final String CONNECT_TYPE_INT16 = "int16";

  private static final long MILLIS_PER_DAY = 24 * 60 * 60 * 1000;
  private static final int MILLIS_PER_NANO = 1_000_000;
  private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

  // Convert values in Kafka Connect form into their logical types. These logical converters are
  // discovered by logical type
  // names specified in the field
  private static final HashMap<String, LogicalTypeConverter>
      TO_CONNECT_LOGICAL_CONVERTERS = new HashMap<>();

  static {
    TO_CONNECT_LOGICAL_CONVERTERS.put(Decimal.LOGICAL_NAME, (schema, value) -> {
      if (!(value instanceof Message)) {
        throw new DataException("Invalid type for Date, "
            + "expected Message but was "
            + value.getClass());
      }
      return DecimalUtils.toBigDecimal((Message) value);
    });

    TO_CONNECT_LOGICAL_CONVERTERS.put(Date.LOGICAL_NAME, (schema, value) -> {
      if (!(value instanceof Message)) {
        throw new DataException("Invalid type for Date, "
            + "expected Message but was "
            + value.getClass());
      }
      Message message = (Message) value;
      int year = 0;
      int month = 0;
      int day = 0;
      for (Map.Entry<FieldDescriptor, Object> entry : message.getAllFields().entrySet()) {
        if (entry.getKey().getName().equals("year")) {
          year = ((Number) entry.getValue()).intValue();
        } else if (entry.getKey().getName().equals("month")) {
          month = ((Number) entry.getValue()).intValue();
        } else if (entry.getKey().getName().equals("day")) {
          day = ((Number) entry.getValue()).intValue();
        }
      }
      Calendar cal = Calendar.getInstance(UTC);
      cal.setLenient(false);
      cal.set(Calendar.YEAR, year);
      cal.set(Calendar.MONTH, month - 1);
      cal.set(Calendar.DAY_OF_MONTH, day);
      cal.set(Calendar.HOUR_OF_DAY, 0);
      cal.set(Calendar.MINUTE, 0);
      cal.set(Calendar.SECOND, 0);
      cal.set(Calendar.MILLISECOND, 0);
      return cal.getTime();
    });

    TO_CONNECT_LOGICAL_CONVERTERS.put(Time.LOGICAL_NAME, (schema, value) -> {
      if (!(value instanceof Message)) {
        throw new DataException("Invalid type for Time, "
            + "expected Message but was "
            + value.getClass());
      }
      Message message = (Message) value;
      int hours = 0;
      int minutes = 0;
      int seconds = 0;
      int nanos = 0;
      for (Map.Entry<FieldDescriptor, Object> entry : message.getAllFields().entrySet()) {
        if (entry.getKey().getName().equals("hours")) {
          hours = ((Number) entry.getValue()).intValue();
        } else if (entry.getKey().getName().equals("minutes")) {
          minutes = ((Number) entry.getValue()).intValue();
        } else if (entry.getKey().getName().equals("seconds")) {
          seconds = ((Number) entry.getValue()).intValue();
        } else if (entry.getKey().getName().equals("nanos")) {
          nanos = ((Number) entry.getValue()).intValue();
        }
      }
      LocalTime localTime = LocalTime.of(hours, minutes, seconds, nanos);
      return new java.util.Date(localTime.toNanoOfDay() / MILLIS_PER_NANO);
    });

    TO_CONNECT_LOGICAL_CONVERTERS.put(Timestamp.LOGICAL_NAME, (schema, value) -> {
      if (!(value instanceof Message)) {
        throw new DataException("Invalid type for Timestamp, "
            + "expected Message but was "
            + value.getClass());
      }
      Message message = (Message) value;
      long seconds = 0L;
      int nanos = 0;
      for (Map.Entry<FieldDescriptor, Object> entry : message.getAllFields().entrySet()) {
        if (entry.getKey().getName().equals("seconds")) {
          seconds = ((Number) entry.getValue()).longValue();
        } else if (entry.getKey().getName().equals("nanos")) {
          nanos = ((Number) entry.getValue()).intValue();
        }
      }
      com.google.protobuf.Timestamp timestamp = com.google.protobuf.Timestamp.newBuilder()
          .setSeconds(seconds)
          .setNanos(nanos)
          .build();
      return Timestamp.toLogical(schema, Timestamps.toMillis(timestamp));
    });
  }

  private static final HashMap<String, LogicalTypeConverter>
      TO_PROTOBUF_LOGICAL_CONVERTERS = new HashMap<>();

  static {
    TO_PROTOBUF_LOGICAL_CONVERTERS.put(Decimal.LOGICAL_NAME, (schema, value) -> {
      if (!(value instanceof BigDecimal)) {
        throw new DataException("Invalid type for Decimal, "
            + "expected BigDecimal but was " + value.getClass());
      }
      return DecimalUtils.fromBigDecimal((BigDecimal) value);
    });

    TO_PROTOBUF_LOGICAL_CONVERTERS.put(Date.LOGICAL_NAME, (schema, value) -> {
      if (!(value instanceof java.util.Date)) {
        throw new DataException("Invalid type for Date, expected Date but was " + value.getClass());
      }
      java.util.Date date = (java.util.Date) value;
      Calendar calendar = Calendar.getInstance(UTC);
      calendar.setTime(date);
      if (calendar.get(Calendar.HOUR_OF_DAY) != 0 || calendar.get(Calendar.MINUTE) != 0
          || calendar.get(Calendar.SECOND) != 0 || calendar.get(Calendar.MILLISECOND) != 0) {
        throw new DataException(
            "Kafka Connect Date type should not have any time fields set to non-zero values.");
      }
      return com.google.type.Date.newBuilder()
          .setYear(calendar.get(Calendar.YEAR))
          .setMonth(calendar.get(Calendar.MONTH) + 1)
          .setDay(calendar.get(Calendar.DAY_OF_MONTH))
          .build();
    });

    TO_PROTOBUF_LOGICAL_CONVERTERS.put(Time.LOGICAL_NAME, (schema, value) -> {
      if (!(value instanceof java.util.Date)) {
        throw new DataException("Invalid type for Time, expected Date but was " + value.getClass());
      }
      java.util.Date date = (java.util.Date) value;
      Calendar calendar = Calendar.getInstance(UTC);
      calendar.setTime(date);
      long unixMillis = calendar.getTimeInMillis();
      if (unixMillis < 0 || unixMillis > MILLIS_PER_DAY) {
        throw new DataException(
            "Time values must use number of millis greater than 0 and less than 86400000");
      }
      return com.google.type.TimeOfDay.newBuilder()
          .setHours(calendar.get(Calendar.HOUR_OF_DAY))
          .setMinutes(calendar.get(Calendar.MINUTE))
          .setSeconds(calendar.get(Calendar.SECOND))
          .setNanos(calendar.get(Calendar.MILLISECOND) * MILLIS_PER_NANO)
          .build();
    });

    TO_PROTOBUF_LOGICAL_CONVERTERS.put(Timestamp.LOGICAL_NAME, (schema, value) -> {
      if (!(value instanceof java.util.Date)) {
        throw new DataException("Invalid type for Timestamp, "
            + "expected Date but was " + value.getClass());
      }
      java.util.Date date = (java.util.Date) value;
      return Timestamps.fromMillis(Timestamp.fromLogical(schema, date));
    });
  }

  private static Pattern NAME_START_CHAR = Pattern.compile("^[A-Za-z]");  // underscore not allowed
  private static Pattern NAME_INVALID_CHARS = Pattern.compile("[^A-Za-z0-9_]");

  private int defaultSchemaNameIndex = 0;

  private final Cache<Schema, ProtobufSchema> fromConnectSchemaCache;
  private final Cache<Pair<String, ProtobufSchema>, Schema> toConnectSchemaCache;
  private boolean enhancedSchemaSupport;
  private boolean scrubInvalidNames;
  private boolean useWrapperForNullables;

  public ProtobufData() {
    this(new ProtobufDataConfig.Builder().with(
        SCHEMAS_CACHE_SIZE_CONFIG,
        SCHEMAS_CACHE_SIZE_DEFAULT
    ).build());
  }

  public ProtobufData(int cacheSize) {
    this(new ProtobufDataConfig.Builder().with(SCHEMAS_CACHE_SIZE_CONFIG, cacheSize).build());
  }

  public ProtobufData(ProtobufDataConfig protobufDataConfig) {
    fromConnectSchemaCache =
        new SynchronizedCache<>(new LRUCache<>(protobufDataConfig.schemaCacheSize()));
    toConnectSchemaCache =
        new SynchronizedCache<>(new LRUCache<>(protobufDataConfig.schemaCacheSize()));
    this.enhancedSchemaSupport = protobufDataConfig.isEnhancedProtobufSchemaSupport();
    this.scrubInvalidNames = protobufDataConfig.isScrubInvalidNames();
    this.useWrapperForNullables = protobufDataConfig.useWrapperForNullables();
  }

  /**
   * Convert this object, in Connect data format, into an Protobuf object.
   */
  public ProtobufSchemaAndValue fromConnectData(Schema schema, Object value) {
    ProtobufSchema protobufSchema = fromConnectSchema(schema);
    Object ctx = null;
    if (schema != null) {
      String name = schema.name();
      if (name == null) {
        name = DEFAULT_SCHEMA_NAME + "1";
      }
      ctx = protobufSchema.toDescriptor(name);
    }
    return new ProtobufSchemaAndValue(
        protobufSchema,
        fromConnectData(ctx, schema, "", value, protobufSchema)
    );
  }

  // Visible for testing
  protected ProtobufSchemaAndValue fromConnectData(SchemaAndValue schemaAndValue) {
    return fromConnectData(schemaAndValue.schema(), schemaAndValue.value());
  }

  private Object fromConnectData(
      Object ctx,
      Schema schema,
      String scope,
      Object value,
      ProtobufSchema protobufSchema
  ) {
    if (value == null) {
      // Ignore missing values
      return null;
    }

    if (schema.name() != null) {
      LogicalTypeConverter logicalConverter = TO_PROTOBUF_LOGICAL_CONVERTERS.get(schema.name());
      if (logicalConverter != null) {
        return logicalConverter.convert(schema, value);
      }
    }

    final Schema.Type schemaType = schema.type();
    try {
      switch (schemaType) {
        case INT8:
        case INT16:
        case INT32: {
          final int intValue = ((Number) value).intValue(); // Check for correct type
          return useWrapperForNullables && schema.isOptional()
              ? Int32Value.newBuilder().setValue(intValue).build() : intValue;
        }

        case INT64: {
          String protobufType = schema.parameters() != null
              ? schema.parameters().get(PROTOBUF_TYPE_PROP) : null;
          if (Objects.equals(protobufType, "uint32") || Objects.equals(protobufType, "fixed32")) {
            final int intValue = (int) ((Number) value).longValue(); // Check for correct type
            return useWrapperForNullables && schema.isOptional()
                ? Int32Value.newBuilder().setValue(intValue).build() : intValue;
          } else {
            final long longValue = ((Number) value).longValue(); // Check for correct type
            return useWrapperForNullables && schema.isOptional()
                ? Int64Value.newBuilder().setValue(longValue).build() : longValue;
          }
        }

        case FLOAT32: {
          final float floatValue = ((Number) value).floatValue(); // Check for correct type
          return useWrapperForNullables && schema.isOptional()
              ? FloatValue.newBuilder().setValue(floatValue).build() : floatValue;
        }

        case FLOAT64: {
          final double doubleValue = ((Number) value).doubleValue(); // Check for correct type
          return useWrapperForNullables && schema.isOptional()
              ? DoubleValue.newBuilder().setValue(doubleValue).build() : doubleValue;
        }

        case BOOLEAN: {
          final Boolean boolValue = (Boolean) value; // Check for correct type
          return useWrapperForNullables && schema.isOptional()
              ? BoolValue.newBuilder().setValue(boolValue).build() : boolValue;
        }

        case STRING: {
          final String stringValue = (String) value; // Check for correct type
          if (schema.parameters() != null && schema.parameters().containsKey(PROTOBUF_TYPE_ENUM)) {
            String enumType = schema.parameters().get(PROTOBUF_TYPE_ENUM);
            String tag = schema.parameters().get(PROTOBUF_TYPE_ENUM_PREFIX + stringValue);
            if (tag != null) {
              return protobufSchema.getEnumValue(scope + enumType, Integer.parseInt(tag));
            }
          }
          return useWrapperForNullables && schema.isOptional()
              ? StringValue.newBuilder().setValue(stringValue).build() : stringValue;
        }

        case BYTES: {
          final ByteBuffer bytesValue = value instanceof byte[]
              ? ByteBuffer.wrap((byte[]) value)
              : (ByteBuffer) value;
          ByteString byteString = ByteString.copyFrom(bytesValue);
          return useWrapperForNullables && schema.isOptional()
              ? BytesValue.newBuilder().setValue(byteString).build() : byteString;
        }
        case ARRAY:
          final Collection<?> listValue = (Collection<?>) value;
          if (listValue.isEmpty()) {
            return null;
          }
          List<Object> newListValue = new ArrayList<>();
          for (Object o : listValue) {
            newListValue.add(fromConnectData(ctx, schema.valueSchema(), scope, o, protobufSchema));
          }
          return newListValue;
        case MAP:
          final Map<?, ?> mapValue = (Map<?, ?>) value;
          String scopedMapName = ((Descriptor) ctx).getFullName();
          List<Message> newMapValue = new ArrayList<>();
          for (Map.Entry<?, ?> mapEntry : mapValue.entrySet()) {
            DynamicMessage.Builder mapBuilder = protobufSchema.newMessageBuilder(scopedMapName);
            if (mapBuilder == null) {
              throw new IllegalStateException("Invalid message name: " + scopedMapName);
            }
            Descriptor mapDescriptor = mapBuilder.getDescriptorForType();
            final FieldDescriptor keyDescriptor = mapDescriptor.findFieldByName(KEY_FIELD);
            final FieldDescriptor valueDescriptor = mapDescriptor.findFieldByName(VALUE_FIELD);
            Object entryKey = fromConnectData(
                getFieldType(keyDescriptor),
                schema.keySchema(),
                scopedMapName + ".",
                mapEntry.getKey(),
                protobufSchema
            );
            Object entryValue = fromConnectData(
                getFieldType(valueDescriptor),
                schema.valueSchema(),
                scopedMapName + ".",
                mapEntry.getValue(),
                protobufSchema
            );
            mapBuilder.setField(keyDescriptor, entryKey);
            mapBuilder.setField(valueDescriptor, entryValue);
            newMapValue.add(mapBuilder.build());
          }
          return newMapValue;
        case STRUCT:
          final Struct struct = (Struct) value;
          if (!struct.schema().equals(schema)) {
            throw new DataException("Mismatching struct schema");
          }
          String structName = schema.name();
          //This handles the inverting of a union which is held as a struct, where each field is
          // one of the union types.
          if (structName != null && structName.startsWith(PROTOBUF_TYPE_UNION_PREFIX)) {
            for (Field field : schema.fields()) {
              Object object = struct.get(field);
              if (object != null) {
                Object fieldCtx = getFieldType(ctx, field.name());
                return new Pair<>(field.name(),
                    fromConnectData(fieldCtx, field.schema(), scope, object, protobufSchema)
                );
              }
            }
            throw new DataException("Cannot find non-null field");
          } else {
            String scopedStructName = ((Descriptor) ctx).getFullName();
            DynamicMessage.Builder messageBuilder =
                protobufSchema.newMessageBuilder(scopedStructName);
            if (messageBuilder == null) {
              throw new DataException("Invalid message name: " + scopedStructName);
            }
            for (Field field : schema.fields()) {
              Object fieldCtx = getFieldType(ctx, field.name());
              Object fieldValue = fromConnectData(
                  fieldCtx,
                  field.schema(),
                  scopedStructName + ".",
                  struct.get(field),
                  protobufSchema
              );
              if (fieldValue != null) {
                FieldDescriptor fieldDescriptor;
                if (fieldValue instanceof Pair) {
                  Pair<String, Object> union = (Pair<String, Object>) fieldValue;
                  fieldDescriptor = messageBuilder.getDescriptorForType()
                      .findFieldByName(union.getKey());
                  fieldValue = union.getValue();
                } else {
                  fieldDescriptor = messageBuilder.getDescriptorForType()
                      .findFieldByName(field.name());
                }
                if (fieldDescriptor == null) {
                  throw new DataException("Cannot find field with name " + field.name());
                }
                messageBuilder.setField(fieldDescriptor, fieldValue);
              }
            }
            return messageBuilder.build();
          }

        default:
          throw new DataException("Unknown schema type: " + schema.type());
      }
    } catch (ClassCastException e) {
      throw new DataException("Invalid type for " + schema.type() + ": " + value.getClass());
    }
  }

  private Object getFieldType(Object ctx, String name) {
    FieldDescriptor field = ((Descriptor) ctx).findFieldByName(name);
    if (field == null) {
      // Could not find a field with this name, which is the case with oneOfs.
      // In this case we just return the current Descriptor context,
      // since finding oneOf field names can be achieved with the enclosing Descriptor.
      return ctx;
    }
    return getFieldType(field);
  }

  private Object getFieldType(FieldDescriptor field) {
    switch (field.getJavaType()) {
      case MESSAGE:
        return field.getMessageType();
      case ENUM:
        return field.getEnumType();
      default:
        return field.getJavaType();
    }
  }

  static class Pair<K, V> {

    private K key;
    private V value;

    public Pair(K key, V value) {
      this.key = key;
      this.value = value;
    }

    public K getKey() {
      return key;
    }

    public V getValue() {
      return value;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Pair<?, ?> pair = (Pair<?, ?>) o;
      return Objects.equals(key, pair.key)
          && Objects.equals(value, pair.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(key, value);
    }

    @Override
    public String toString() {
      return "Pair{"
          + "key=" + key
          + ", value=" + value
          + '}';
    }
  }

  public ProtobufSchema fromConnectSchema(Schema schema) {
    if (schema == null) {
      return null;
    }
    ProtobufSchema cachedSchema = fromConnectSchemaCache.get(schema);
    if (cachedSchema != null) {
      return cachedSchema;
    }
    String fullName = getNameOrDefault(schema.name());
    String[] split = splitName(fullName);
    String namespace = split[0];
    String name = scrubName(split[1]);
    FromConnectContext ctx = new FromConnectContext();
    ctx.add(fullName);
    ProtobufSchema resultSchema = new ProtobufSchema(
        rawSchemaFromConnectSchema(ctx, namespace, name, schema).getMessageDescriptor(name)
    );
    fromConnectSchemaCache.put(schema, resultSchema);
    return resultSchema;
  }

  /*
   * DynamicSchema is used as a temporary helper class and should not be exposed in the API.
   */
  private DynamicSchema rawSchemaFromConnectSchema(
      FromConnectContext ctx, String namespace, String name, Schema rootElem) {
    if (rootElem.type() != Schema.Type.STRUCT) {
      throw new IllegalArgumentException("Unsupported root schema of type " + rootElem.type());
    }
    try {
      DynamicSchema.Builder schema = DynamicSchema.newBuilder();
      schema.setSyntax(ProtobufSchema.PROTO3);
      if (namespace != null) {
        schema.setPackage(namespace);
      }
      schema.addMessageDefinition(messageDefinitionFromConnectSchema(ctx, schema, name, rootElem));
      return schema.build();
    } catch (Descriptors.DescriptorValidationException e) {
      throw new IllegalStateException(e);
    }
  }

  private MessageDefinition messageDefinitionFromConnectSchema(
      FromConnectContext ctx, DynamicSchema.Builder schema, String name, Schema messageElem
  ) {
    MessageDefinition.Builder message = MessageDefinition.newBuilder(name);
    int index = 1;
    for (Field field : messageElem.fields()) {
      Schema fieldSchema = field.schema();
      String fieldTag = fieldSchema.parameters() != null ? fieldSchema.parameters()
          .get(PROTOBUF_TYPE_TAG) : null;
      int tag = fieldTag != null ? Integer.parseInt(fieldTag) : index++;
      FieldDefinition fieldDef = fieldDefinitionFromConnectSchema(
          ctx,
          schema,
          message,
          fieldSchema,
          scrubName(field.name()),
          tag
      );
      if (fieldDef != null) {
        message.addField(fieldDef.getLabel(),
            fieldDef.getType(),
            fieldDef.getName(),
            fieldDef.getNum(),
            fieldDef.getDefaultVal(),
            fieldDef.getDoc(),
            fieldDef.getParams()
        );
      }
    }
    return message.build();
  }

  private void oneofDefinitionFromConnectSchema(
      FromConnectContext ctx,
      DynamicSchema.Builder schema,
      MessageDefinition.Builder message,
      Schema unionElem,
      String unionName
  ) {
    MessageDefinition.OneofBuilder oneof = message.addOneof(unionName);
    for (Field field : unionElem.fields()) {
      Schema fieldSchema = field.schema();
      String fieldTag = fieldSchema.parameters() != null ? fieldSchema.parameters()
          .get(PROTOBUF_TYPE_TAG) : null;
      int tag = fieldTag != null ? Integer.parseInt(fieldTag) : 0;
      FieldDefinition fieldDef = fieldDefinitionFromConnectSchema(
          ctx,
          schema,
          message,
          field.schema(),
          scrubName(field.name()),
          tag
      );
      if (fieldDef != null) {
        oneof.addField(
            fieldDef.getType(),
            fieldDef.getName(),
            fieldDef.getNum(),
            fieldDef.getDefaultVal(),
            fieldDef.getDoc(),
            fieldDef.getParams()
        );
      }
    }
  }

  private FieldDefinition fieldDefinitionFromConnectSchema(
      FromConnectContext ctx,
      DynamicSchema.Builder schema,
      MessageDefinition.Builder message,
      Schema fieldSchema,
      String name,
      int tag
  ) {
    String label = null;
    if (fieldSchema.type() == Schema.Type.ARRAY) {
      label = "repeated";
      fieldSchema = fieldSchema.valueSchema();
    } else if (fieldSchema.type() == Schema.Type.MAP) {
      label = "repeated";
    }
    Map<String, String> params = new HashMap<>();
    String type = dataTypeFromConnectSchema(fieldSchema, name, params);
    if (fieldSchema.type() == Schema.Type.STRUCT) {
      String fieldSchemaName = fieldSchema.name();
      if (fieldSchemaName != null && fieldSchemaName.startsWith(PROTOBUF_TYPE_UNION_PREFIX)) {
        String unionName =
            getUnqualifiedName(fieldSchemaName.substring(PROTOBUF_TYPE_UNION_PREFIX.length()));
        oneofDefinitionFromConnectSchema(ctx, schema, message, fieldSchema, unionName);
        return null;
      } else {
        if (!ctx.contains(fieldSchemaName)) {
          ctx.add(fieldSchemaName);
          message.addMessageDefinition(messageDefinitionFromConnectSchema(
              ctx,
              schema,
              type,
              fieldSchema
          ));
        }
      }
    } else if (fieldSchema.type() == Schema.Type.MAP) {
      message.addMessageDefinition(
          mapDefinitionFromConnectSchema(ctx, schema, type, fieldSchema));
    } else if (fieldSchema.parameters() != null && fieldSchema.parameters()
        .containsKey(PROTOBUF_TYPE_ENUM)) {
      message.addEnumDefinition(enumDefinitionFromConnectSchema(schema, fieldSchema));
    } else {
      DynamicSchema dynamicSchema = typeToDynamicSchema(type);
      if (dynamicSchema != null) {
        schema.addSchema(dynamicSchema);
        schema.addDependency(dynamicSchema.getFileDescriptorProto().getName());
      }
    }
    Object defaultVal = fieldSchema.defaultValue();
    return new FieldDefinition(
        label,
        type,
        name,
        tag,
        defaultVal != null ? defaultVal.toString() : null,
        null,
        params
    );
  }

  private DynamicSchema typeToDynamicSchema(String type) {
    ProtobufSchema dep;
    switch (type) {
      case PROTOBUF_DECIMAL_TYPE:
        dep = new ProtobufSchema(
            io.confluent.protobuf.type.Decimal.getDescriptor());
        return dep.toDynamicSchema(PROTOBUF_DECIMAL_LOCATION);
      case PROTOBUF_DATE_TYPE:
        dep = new ProtobufSchema(com.google.type.Date.getDescriptor());
        return dep.toDynamicSchema(PROTOBUF_DATE_LOCATION);
      case PROTOBUF_TIME_TYPE:
        dep = new ProtobufSchema(com.google.type.TimeOfDay.getDescriptor());
        return dep.toDynamicSchema(PROTOBUF_TIME_LOCATION);
      case PROTOBUF_TIMESTAMP_TYPE:
        dep = new ProtobufSchema(com.google.protobuf.Timestamp.getDescriptor());
        return dep.toDynamicSchema(PROTOBUF_TIMESTAMP_LOCATION);
      case PROTOBUF_DOUBLE_WRAPPER_TYPE:
        dep = new ProtobufSchema(com.google.protobuf.DoubleValue.getDescriptor());
        return dep.toDynamicSchema(PROTOBUF_WRAPPER_LOCATION);
      case PROTOBUF_FLOAT_WRAPPER_TYPE:
        dep = new ProtobufSchema(com.google.protobuf.FloatValue.getDescriptor());
        return dep.toDynamicSchema(PROTOBUF_WRAPPER_LOCATION);
      case PROTOBUF_INT64_WRAPPER_TYPE:
        dep = new ProtobufSchema(com.google.protobuf.Int64Value.getDescriptor());
        return dep.toDynamicSchema(PROTOBUF_WRAPPER_LOCATION);
      case PROTOBUF_UINT64_WRAPPER_TYPE:
        dep = new ProtobufSchema(com.google.protobuf.UInt64Value.getDescriptor());
        return dep.toDynamicSchema(PROTOBUF_WRAPPER_LOCATION);
      case PROTOBUF_INT32_WRAPPER_TYPE:
        dep = new ProtobufSchema(com.google.protobuf.Int32Value.getDescriptor());
        return dep.toDynamicSchema(PROTOBUF_WRAPPER_LOCATION);
      case PROTOBUF_UINT32_WRAPPER_TYPE:
        dep = new ProtobufSchema(com.google.protobuf.UInt32Value.getDescriptor());
        return dep.toDynamicSchema(PROTOBUF_WRAPPER_LOCATION);
      case PROTOBUF_BOOL_WRAPPER_TYPE:
        dep = new ProtobufSchema(com.google.protobuf.BoolValue.getDescriptor());
        return dep.toDynamicSchema(PROTOBUF_WRAPPER_LOCATION);
      case PROTOBUF_STRING_WRAPPER_TYPE:
        dep = new ProtobufSchema(com.google.protobuf.StringValue.getDescriptor());
        return dep.toDynamicSchema(PROTOBUF_WRAPPER_LOCATION);
      case PROTOBUF_BYTES_WRAPPER_TYPE:
        dep = new ProtobufSchema(com.google.protobuf.BytesValue.getDescriptor());
        return dep.toDynamicSchema(PROTOBUF_WRAPPER_LOCATION);
      default:
        return null;
    }
  }

  static class FieldDefinition {

    private final String label;
    private final String type;
    private final String name;
    private final int num;
    private final String defaultVal;
    private final String doc;
    private final Map<String, String> params;

    public FieldDefinition(String label, String type, String name, int num, String defaultVal,
        String doc, Map<String, String> params) {
      this.label = label;
      this.type = type;
      this.name = name;
      this.num = num;
      this.defaultVal = defaultVal;
      this.doc = doc;
      this.params = params;
    }

    public String getType() {
      return type;
    }

    public String getName() {
      return name;
    }

    public int getNum() {
      return num;
    }

    public String getDefaultVal() {
      return defaultVal;
    }

    public String getLabel() {
      return label;
    }

    public String getDoc() {
      return doc;
    }

    public Map<String, String> getParams() {
      return params;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FieldDefinition that = (FieldDefinition) o;
      return num == that.num
          && Objects.equals(label, that.label)
          && Objects.equals(type, that.type)
          && Objects.equals(name, that.name)
          && Objects.equals(defaultVal, that.defaultVal)
          && Objects.equals(doc, that.doc)
          && Objects.equals(params, that.params);
    }

    @Override
    public int hashCode() {
      return Objects.hash(label, type, name, num, defaultVal, doc, params);
    }
  }

  private MessageDefinition mapDefinitionFromConnectSchema(
      FromConnectContext ctx, DynamicSchema.Builder schema, String name, Schema mapElem
  ) {
    MessageDefinition.Builder map = MessageDefinition.newBuilder(name);
    FieldDefinition key = fieldDefinitionFromConnectSchema(
        ctx,
        schema,
        map,
        mapElem.keySchema(),
        KEY_FIELD,
        1
    );
    map.addField(key.getLabel(), key.getType(), key.getName(), key.getNum(),
        key.getDefaultVal(), null, null);
    FieldDefinition val = fieldDefinitionFromConnectSchema(
        ctx,
        schema,
        map,
        mapElem.valueSchema(),
        VALUE_FIELD,
        2
    );
    map.addField(val.getLabel(), val.getType(), val.getName(), val.getNum(),
        val.getDefaultVal(), null, null);
    return map.build();
  }

  private EnumDefinition enumDefinitionFromConnectSchema(
      DynamicSchema.Builder schema,
      Schema enumElem
  ) {
    String enumName = getUnqualifiedName(enumElem.name());
    EnumDefinition.Builder enumer = EnumDefinition.newBuilder(enumName);
    for (Map.Entry<String, String> entry : enumElem.parameters().entrySet()) {
      if (entry.getKey().startsWith(PROTOBUF_TYPE_ENUM_PREFIX)) {
        String name = entry.getKey().substring(PROTOBUF_TYPE_ENUM_PREFIX.length());
        int tag = Integer.parseInt(entry.getValue());
        enumer.addValue(name, tag);
      }
    }
    return enumer.build();
  }

  private String dataTypeFromConnectSchema(
      Schema schema, String fieldName, Map<String, String> params) {
    if (isDecimalSchema(schema)) {
      if (schema.parameters() != null) {
        String precision = schema.parameters().get(CONNECT_PRECISION_PROP);
        if (precision != null) {
          params.put(PROTOBUF_PRECISION_PROP, precision);
        }
        String scale = schema.parameters().get(CONNECT_SCALE_PROP);
        if (scale != null) {
          params.put(PROTOBUF_SCALE_PROP, scale);
        }
      }
      return PROTOBUF_DECIMAL_TYPE;
    } else if (isDateSchema(schema)) {
      return PROTOBUF_DATE_TYPE;
    } else if (isTimeSchema(schema)) {
      return PROTOBUF_TIME_TYPE;
    } else if (isTimestampSchema(schema)) {
      return PROTOBUF_TIMESTAMP_TYPE;
    }
    String defaultType;
    switch (schema.type()) {
      case INT8:
        params.put(CONNECT_TYPE_PROP, CONNECT_TYPE_INT8);
        return useWrapperForNullables && schema.isOptional()
            ? PROTOBUF_INT32_WRAPPER_TYPE : FieldDescriptor.Type.INT32.toString().toLowerCase();
      case INT16:
        params.put(CONNECT_TYPE_PROP, CONNECT_TYPE_INT16);
        return useWrapperForNullables && schema.isOptional()
            ? PROTOBUF_INT32_WRAPPER_TYPE : FieldDescriptor.Type.INT32.toString().toLowerCase();
      case INT32:
        defaultType = FieldDescriptor.Type.INT32.toString().toLowerCase();
        if (schema.parameters() != null && schema.parameters().containsKey(PROTOBUF_TYPE_PROP)) {
          defaultType = schema.parameters().get(PROTOBUF_TYPE_PROP);
        }
        return useWrapperForNullables && schema.isOptional()
            ? PROTOBUF_INT32_WRAPPER_TYPE : defaultType;
      case INT64:
        defaultType = FieldDescriptor.Type.INT64.toString().toLowerCase();
        if (schema.parameters() != null && schema.parameters().containsKey(PROTOBUF_TYPE_PROP)) {
          defaultType = schema.parameters().get(PROTOBUF_TYPE_PROP);
        }
        String wrapperType;
        switch (defaultType) {
          case "uint32":
          case "fixed32":
            wrapperType = PROTOBUF_UINT32_WRAPPER_TYPE;
            break;
          case "uint64":
          case "fixed64":
            wrapperType = PROTOBUF_UINT64_WRAPPER_TYPE;
            break;
          default:
            wrapperType = PROTOBUF_INT64_WRAPPER_TYPE;
        }
        return useWrapperForNullables && schema.isOptional()
            ? wrapperType : defaultType;
      case FLOAT32:
        return useWrapperForNullables && schema.isOptional()
            ? PROTOBUF_FLOAT_WRAPPER_TYPE : FieldDescriptor.Type.FLOAT.toString().toLowerCase();
      case FLOAT64:
        return useWrapperForNullables && schema.isOptional()
            ? PROTOBUF_DOUBLE_WRAPPER_TYPE : FieldDescriptor.Type.DOUBLE.toString().toLowerCase();
      case BOOLEAN:
        return useWrapperForNullables && schema.isOptional()
            ? PROTOBUF_BOOL_WRAPPER_TYPE : FieldDescriptor.Type.BOOL.toString().toLowerCase();
      case STRING:
        if (schema.parameters() != null && schema.parameters().containsKey(PROTOBUF_TYPE_ENUM)) {
          return schema.parameters().get(PROTOBUF_TYPE_ENUM);
        }
        return useWrapperForNullables && schema.isOptional()
            ? PROTOBUF_STRING_WRAPPER_TYPE : FieldDescriptor.Type.STRING.toString().toLowerCase();
      case BYTES:
        return useWrapperForNullables && schema.isOptional()
            ? PROTOBUF_BYTES_WRAPPER_TYPE : FieldDescriptor.Type.BYTES.toString().toLowerCase();
      case ARRAY:
        // Array should not occur here
        throw new IllegalArgumentException("Array cannot be nested");
      case MAP:
        return ProtobufSchema.toMapEntry(getUnqualifiedName(schema.name()));
      case STRUCT:
        String name = getUnqualifiedName(schema.name());
        if (name.equals(fieldName)) {
          // Can't have message types and fields with same name, add suffix to message type
          name += "Message";
        }
        return name;
      default:
        throw new DataException("Unknown schema type: " + schema.type());
    }
  }

  private boolean isDecimalSchema(Schema schema) {
    return Decimal.LOGICAL_NAME.equals(schema.name());
  }

  private boolean isDateSchema(Schema schema) {
    return Date.LOGICAL_NAME.equals(schema.name());
  }

  private boolean isTimeSchema(Schema schema) {
    return Time.LOGICAL_NAME.equals(schema.name());
  }

  private boolean isTimestampSchema(Schema schema) {
    return Timestamp.LOGICAL_NAME.equals(schema.name());
  }

  public SchemaAndValue toConnectData(ProtobufSchema protobufSchema, Message message) {
    if (message == null) {
      return SchemaAndValue.NULL;
    }

    Schema schema = toConnectSchema(protobufSchema);
    return new SchemaAndValue(schema, toConnectData(schema, message));
  }

  // Visible for testing
  @SuppressWarnings("unchecked")
  protected Object toConnectData(Schema schema, Object value) {
    try {
      if (value == null) {
        return null;
      }

      if (schema.name() != null) {
        LogicalTypeConverter logicalConverter = TO_CONNECT_LOGICAL_CONVERTERS.get(schema.name());
        if (logicalConverter != null) {
          return logicalConverter.convert(schema, value);
        }
      }

      Object converted = null;
      switch (schema.type()) {
        case INT8:
          converted = useWrapperForNullables && schema.isOptional()
              ? getWrappedValue((Message) value) : ((Number) value).byteValue();
          break;
        case INT16:
          converted = useWrapperForNullables && schema.isOptional()
              ? getWrappedValue((Message) value) : ((Number) value).shortValue();
          break;
        case INT32:
          converted = useWrapperForNullables && schema.isOptional()
              ? getWrappedValue((Message) value) : ((Number) value).intValue();
          break;
        case INT64:
          if (useWrapperForNullables && schema.isOptional()) {
            converted = getWrappedValue((Message) value);
          } else {
            long longValue;
            if (value instanceof Long) {
              longValue = (Long) value;
            } else {
              longValue = Integer.toUnsignedLong(((Number) value).intValue());
            }
            converted = longValue;
          }
          break;
        case FLOAT32:
          converted = useWrapperForNullables && schema.isOptional()
              ? getWrappedValue((Message) value) : ((Number) value).floatValue();
          break;
        case FLOAT64:
          converted = useWrapperForNullables && schema.isOptional()
              ? getWrappedValue((Message) value) : ((Number) value).doubleValue();
          break;
        case BOOLEAN:
          converted = useWrapperForNullables && schema.isOptional()
              ? getWrappedValue((Message) value) : (Boolean) value;
          break;
        case STRING:
          if (useWrapperForNullables && schema.isOptional()) {
            converted = getWrappedValue((Message) value);
          } else if (value instanceof String) {
            converted = value;
          } else if (value instanceof CharSequence
              || value instanceof Enum
              || value instanceof EnumValueDescriptor) {
            converted = value.toString();
          } else {
            throw new DataException("Invalid class for string type, expecting String or "
                + "CharSequence but found "
                + value.getClass());
          }
          break;
        case BYTES:
          if (useWrapperForNullables && schema.isOptional()) {
            converted = ((ByteString) getWrappedValue((Message) value)).asReadOnlyByteBuffer();
          } else if (value instanceof byte[]) {
            converted = ByteBuffer.wrap((byte[]) value);
          } else if (value instanceof ByteBuffer) {
            converted = value;
          } else if (value instanceof ByteString) {
            converted = ((ByteString) value).asReadOnlyByteBuffer();
          } else {
            throw new DataException("Invalid class for bytes type, expecting byte[], ByteBuffer, "
                + "or ByteString but found "
                + value.getClass());
          }
          break;
        case ARRAY:
          final Schema elemSchema = schema.valueSchema();
          final Collection<Object> array = (Collection<Object>) value;
          final List<Object> newArray = new ArrayList<>(array.size());
          for (Object elem : array) {
            newArray.add(toConnectData(elemSchema, elem));
          }
          converted = newArray;
          break;
        case MAP:
          final Schema keySchema = schema.keySchema();
          final Schema valueSchema = schema.valueSchema();
          final Collection<? extends Message> map = (Collection<? extends Message>) value;
          final Map<Object, Object> newMap = new HashMap<>();
          for (Message message : map) {
            Descriptor descriptor = message.getDescriptorForType();
            Object elemKey = message.getField(descriptor.findFieldByName(KEY_FIELD));
            Object elemValue = message.getField(descriptor.findFieldByName(VALUE_FIELD));
            newMap.put(toConnectData(keySchema, elemKey), toConnectData(valueSchema, elemValue));
          }
          converted = newMap;
          break;
        case STRUCT:
          final Message message = (Message) value;
          final Struct struct = new Struct(schema.schema());
          final Descriptor descriptor = message.getDescriptorForType();

          for (OneofDescriptor oneOfDescriptor : descriptor.getOneofs()) {
            if (message.hasOneof(oneOfDescriptor)) {
              FieldDescriptor fieldDescriptor = message.getOneofFieldDescriptor(oneOfDescriptor);
              Object obj = message.getField(fieldDescriptor);
              if (obj != null) {
                setUnionField(schema, message, struct, oneOfDescriptor, fieldDescriptor);
                break;
              }
            }
          }

          for (FieldDescriptor fieldDescriptor : descriptor.getFields()) {
            OneofDescriptor oneOfDescriptor = fieldDescriptor.getContainingOneof();
            if (oneOfDescriptor != null) {
              // Already added field as oneof
              continue;
            }
            if (fieldDescriptor.getJavaType() != FieldDescriptor.JavaType.MESSAGE
                || fieldDescriptor.isRepeated()
                || message.hasField(fieldDescriptor)) {
              setStructField(schema, message, struct, fieldDescriptor);
            }
          }

          converted = struct;
          break;
        default:
          throw new DataException("Unknown Connect schema type: " + schema.type());
      }

      return converted;
    } catch (ClassCastException e) {
      throw new DataException("Invalid type for " + schema.type() + ": " + value.getClass());
    }
  }

  private Object getWrappedValue(Message message) {
    Descriptor descriptor = message.getDescriptorForType();
    FieldDescriptor fieldDescriptor = descriptor.findFieldByName("value");
    return message.getField(fieldDescriptor);
  }

  private void setUnionField(
      Schema schema,
      Message message,
      Struct result,
      OneofDescriptor oneOfDescriptor,
      FieldDescriptor fieldDescriptor
  ) {
    String unionName = oneOfDescriptor.getName() + "_" + oneOfDescriptor.getIndex();
    Field unionField = schema.field(unionName);
    Schema unionSchema = unionField.schema();
    Struct union = new Struct(unionSchema);

    final String fieldName = fieldDescriptor.getName();
    final Field field = unionSchema.field(fieldName);
    Object obj = message.getField(fieldDescriptor);
    union.put(fieldName, toConnectData(field.schema(), obj));

    result.put(unionField, union);
  }

  private void setStructField(
      Schema schema,
      Message message,
      Struct result,
      FieldDescriptor fieldDescriptor
  ) {
    final String fieldName = fieldDescriptor.getName();
    final Field field = schema.field(fieldName);
    Object obj = message.getField(fieldDescriptor);
    result.put(fieldName, toConnectData(field.schema(), obj));
  }

  public Schema toConnectSchema(ProtobufSchema schema) {
    if (schema == null) {
      return null;
    }
    Pair<String, ProtobufSchema> cacheKey = new Pair<>(schema.name(), schema);
    Schema cachedSchema = toConnectSchemaCache.get(cacheKey);
    if (cachedSchema != null) {
      return cachedSchema;
    }
    SchemaBuilder builder = SchemaBuilder.struct();
    Descriptor descriptor = schema.toDescriptor();
    ToConnectContext ctx = new ToConnectContext();
    ctx.put(descriptor.getFullName(), builder);
    Schema resultSchema = toConnectSchema(ctx, builder, descriptor, schema.version()).build();
    toConnectSchemaCache.put(cacheKey, resultSchema);
    return resultSchema;
  }

  private SchemaBuilder toConnectSchema(
      ToConnectContext ctx, SchemaBuilder builder, Descriptor descriptor, Integer version) {
    List<FieldDescriptor> fieldDescriptors = descriptor.getFields();
    if (isMapDescriptor(descriptor, fieldDescriptors)) {
      String name = ProtobufSchema.toMapField(descriptor.getName());
      return SchemaBuilder.map(toConnectSchema(ctx, fieldDescriptors.get(0)),
          toConnectSchema(ctx, fieldDescriptors.get(1))
      )
          .name(name);
    }
    String name = enhancedSchemaSupport ? descriptor.getFullName() : descriptor.getName();
    builder.name(name);
    List<OneofDescriptor> oneOfDescriptors = descriptor.getOneofs();
    for (OneofDescriptor oneOfDescriptor : oneOfDescriptors) {
      String unionName = oneOfDescriptor.getName() + "_" + oneOfDescriptor.getIndex();
      builder.field(unionName, toConnectSchema(ctx, oneOfDescriptor));
    }
    for (FieldDescriptor fieldDescriptor : fieldDescriptors) {
      OneofDescriptor oneOfDescriptor = fieldDescriptor.getContainingOneof();
      if (oneOfDescriptor != null) {
        // Already added field as oneof
        continue;
      }
      builder.field(fieldDescriptor.getName(), toConnectSchema(ctx, fieldDescriptor));
    }

    if (version != null) {
      builder.version(version);
    }

    return builder;
  }

  private Schema toConnectSchema(ToConnectContext ctx, OneofDescriptor descriptor) {
    SchemaBuilder builder = SchemaBuilder.struct();
    builder.name(PROTOBUF_TYPE_UNION_PREFIX + descriptor.getName());
    List<FieldDescriptor> fieldDescriptors = descriptor.getFields();
    for (FieldDescriptor fieldDescriptor : fieldDescriptors) {
      builder.field(fieldDescriptor.getName(), toConnectSchema(ctx, fieldDescriptor));
    }
    builder.optional();
    return builder.build();
  }

  private Schema toConnectSchema(ToConnectContext ctx, FieldDescriptor descriptor) {
    SchemaBuilder builder;

    switch (descriptor.getType()) {
      case INT32:
      case SINT32:
      case SFIXED32: {
        if (descriptor.getOptions().hasExtension(MetaProto.fieldMeta)) {
          Meta fieldMeta = descriptor.getOptions().getExtension(MetaProto.fieldMeta);
          Map<String, String> params = fieldMeta.getParamsMap();
          if (params != null) {
            String connectType = params.get(CONNECT_TYPE_PROP);
            if (CONNECT_TYPE_INT8.equals(connectType)) {
              builder = SchemaBuilder.int8();
              break;
            } else if (CONNECT_TYPE_INT16.equals(connectType)) {
              builder = SchemaBuilder.int16();
              break;
            }
          }
        }
        builder = SchemaBuilder.int32();
        if (descriptor.getType() != FieldDescriptor.Type.INT32) {
          builder.parameter(PROTOBUF_TYPE_PROP, descriptor.getType().toString().toLowerCase());
        }
        break;
      }

      case UINT32:
      case FIXED32:
      case INT64:
      case UINT64:
      case SINT64:
      case FIXED64:
      case SFIXED64: {
        builder = SchemaBuilder.int64();
        if (descriptor.getType() != FieldDescriptor.Type.INT64) {
          builder.parameter(PROTOBUF_TYPE_PROP, descriptor.getType().toString().toLowerCase());
        }
        break;
      }

      case FLOAT: {
        builder = SchemaBuilder.float32();
        break;
      }

      case DOUBLE: {
        builder = SchemaBuilder.float64();
        break;
      }

      case BOOL: {
        builder = SchemaBuilder.bool();
        break;
      }

      case STRING:
        builder = SchemaBuilder.string();
        break;

      case BYTES:
        builder = SchemaBuilder.bytes();
        break;

      case ENUM:
        builder = SchemaBuilder.string();
        EnumDescriptor enumDescriptor = descriptor.getEnumType();
        builder.name(enumDescriptor.getName());
        builder.parameter(PROTOBUF_TYPE_ENUM, enumDescriptor.getName());
        for (EnumValueDescriptor enumValueDesc : enumDescriptor.getValues()) {
          String enumSymbol = enumValueDesc.getName();
          String enumTag = String.valueOf(enumValueDesc.getNumber());
          builder.parameter(PROTOBUF_TYPE_ENUM_PREFIX + enumSymbol, enumTag);
        }
        builder.optional();
        break;

      case MESSAGE: {
        String fullName = descriptor.getMessageType().getFullName();
        switch (fullName) {
          case PROTOBUF_DECIMAL_TYPE:
            Integer precision = null;
            int scale = 0;
            if (descriptor.getOptions().hasExtension(MetaProto.fieldMeta)) {
              Meta fieldMeta = descriptor.getOptions().getExtension(MetaProto.fieldMeta);
              Map<String, String> params = fieldMeta.getParamsMap();
              String precisionStr = params.get(PROTOBUF_PRECISION_PROP);
              if (precisionStr != null) {
                try {
                  precision = Integer.parseInt(precisionStr);
                } catch (NumberFormatException e) {
                  // ignore
                }
              }
              String scaleStr = params.get(PROTOBUF_SCALE_PROP);
              if (scaleStr != null) {
                try {
                  scale = Integer.parseInt(scaleStr);
                } catch (NumberFormatException e) {
                  // ignore
                }
              }
            }
            builder = Decimal.builder(scale);
            if (precision != null) {
              builder.parameter(CONNECT_PRECISION_PROP, precision.toString());
            }
            break;
          case PROTOBUF_DATE_TYPE:
            builder = Date.builder();
            break;
          case PROTOBUF_TIME_TYPE:
            builder = Time.builder();
            break;
          case PROTOBUF_TIMESTAMP_TYPE:
            builder = Timestamp.builder();
            break;
          default:
            builder = toUnwrappedOrStructSchema(ctx, descriptor);
            break;
        }
        builder.optional();
        break;
      }

      default:
        throw new DataException("Unknown Connect schema type: " + descriptor.getType());
    }

    if (descriptor.isRepeated() && builder.type() != Schema.Type.MAP) {
      Schema schema = builder.optional().build();
      builder = SchemaBuilder.array(schema);
      builder.optional();
    }

    if (!useWrapperForNullables) {
      builder.optional();
    }
    builder.parameter(PROTOBUF_TYPE_TAG, String.valueOf(descriptor.getNumber()));
    return builder.build();
  }

  private SchemaBuilder toUnwrappedOrStructSchema(
          ToConnectContext ctx, FieldDescriptor descriptor) {
    if (!useWrapperForNullables) {
      return toStructSchema(ctx, descriptor);
    }
    String fullName = descriptor.getMessageType().getFullName();
    switch (fullName) {
      case PROTOBUF_DOUBLE_WRAPPER_TYPE:
        return SchemaBuilder.float64();
      case PROTOBUF_FLOAT_WRAPPER_TYPE:
        return SchemaBuilder.float32();
      case PROTOBUF_INT64_WRAPPER_TYPE:
        return SchemaBuilder.int64();
      case PROTOBUF_UINT64_WRAPPER_TYPE:
        return SchemaBuilder.int64();
      case PROTOBUF_INT32_WRAPPER_TYPE:
        return SchemaBuilder.int32();
      case PROTOBUF_UINT32_WRAPPER_TYPE:
        return SchemaBuilder.int64();
      case PROTOBUF_BOOL_WRAPPER_TYPE:
        return SchemaBuilder.bool();
      case PROTOBUF_STRING_WRAPPER_TYPE:
        return SchemaBuilder.string();
      case PROTOBUF_BYTES_WRAPPER_TYPE:
        return SchemaBuilder.bytes();
      default:
        return toStructSchema(ctx, descriptor);
    }
  }

  private SchemaBuilder toStructSchema(ToConnectContext ctx, FieldDescriptor descriptor) {
    String fullName = descriptor.getMessageType().getFullName();
    SchemaBuilder builder = ctx.get(fullName);
    if (builder != null) {
      builder = new SchemaWrapper(builder);
    } else {
      builder = SchemaBuilder.struct();
      ctx.put(fullName, builder);
      builder = toConnectSchema(ctx, builder, descriptor.getMessageType(), null);
    }
    return builder;
  }

  private static boolean isMapDescriptor(
      Descriptor descriptor,
      List<FieldDescriptor> fieldDescriptors
  ) {
    return descriptor.getName().endsWith(MAP_ENTRY_SUFFIX)
        && fieldDescriptors.size() == 2
        && fieldDescriptors.get(0).getName().equals(KEY_FIELD)
        && fieldDescriptors.get(1).getName().equals(VALUE_FIELD);
  }

  /**
   * Split a full dotted-syntax name into a namespace and a single-component name.
   */
  private static String[] splitName(String fullName) {
    String[] result = new String[2];
    int indexLastDot = fullName.lastIndexOf('.');
    if (indexLastDot >= 0) {
      result[0] = fullName.substring(0, indexLastDot);
      result[1] = fullName.substring(indexLastDot + 1);
    } else {
      result[0] = null;
      result[1] = fullName;
    }
    return result;
  }

  /**
   * Strip the namespace from a name.
   */
  private String getUnqualifiedName(String name) {
    String fullName = getNameOrDefault(name);
    int indexLastDot = fullName.lastIndexOf('.');
    if (indexLastDot >= 0) {
      return fullName.substring(indexLastDot + 1);
    } else {
      return fullName;
    }
  }

  private String scrubName(String name) {
    return scrubInvalidNames ? doScrubName(name) : name;
  }

  // Visible for testing
  protected static String doScrubName(String name) {
    try {
      if (name == null) {
        return name;
      }
      String encoded = URLEncoder.encode(name, "UTF-8");
      if (!NAME_START_CHAR.matcher(encoded).lookingAt()) {
        encoded = "x" + encoded;  // use an arbitrary valid prefix
      }
      encoded = NAME_INVALID_CHARS.matcher(encoded).replaceAll("_");
      return encoded;
    } catch (UnsupportedEncodingException e) {
      return name;
    }
  }

  private String getNameOrDefault(String name) {
    return name != null && !name.isEmpty()
           ? name
           : DEFAULT_SCHEMA_NAME + (++defaultSchemaNameIndex);
  }

  /**
   * Wraps a SchemaBuilder.
   * The internal builder should never be returned, so that the schema is not built prematurely.
   */
  static class SchemaWrapper extends SchemaBuilder {

    private final SchemaBuilder builder;
    // Parameters that override the ones in builder
    private final Map<String, String> parameters;

    public SchemaWrapper(SchemaBuilder builder) {
      super(Type.STRUCT);
      this.builder = builder;
      this.parameters = new LinkedHashMap<>();
    }

    @Override
    public boolean isOptional() {
      return builder.isOptional();
    }

    @Override
    public SchemaBuilder optional() {
      builder.optional();
      return this;
    }

    @Override
    public SchemaBuilder required() {
      builder.required();
      return this;
    }

    @Override
    public Object defaultValue() {
      return builder.defaultValue();
    }

    @Override
    public SchemaBuilder defaultValue(Object value) {
      builder.defaultValue(value);
      return this;
    }

    @Override
    public String name() {
      return builder.name();
    }

    @Override
    public SchemaBuilder name(String name) {
      builder.name(name);
      return this;
    }

    @Override
    public Integer version() {
      return builder.version();
    }

    @Override
    public SchemaBuilder version(Integer version) {
      builder.version(version);
      return this;
    }

    @Override
    public String doc() {
      return builder.doc();
    }

    @Override
    public SchemaBuilder doc(String doc) {
      builder.doc(doc);
      return this;
    }

    @Override
    public Map<String, String> parameters() {
      Map<String, String> allParameters = new HashMap<>();
      if (builder.parameters() != null) {
        allParameters.putAll(builder.parameters());
      }
      allParameters.putAll(parameters);
      return allParameters;
    }

    @Override
    public SchemaBuilder parameters(Map<String, String> props) {
      parameters.putAll(props);
      return this;
    }

    @Override
    public SchemaBuilder parameter(String propertyName, String propertyValue) {
      parameters.put(propertyName, propertyValue);
      return this;
    }

    @Override
    public Type type() {
      return builder.type();
    }

    @Override
    public List<Field> fields() {
      return builder.fields();
    }

    @Override
    public Field field(String fieldName) {
      return builder.field(fieldName);
    }

    @Override
    public SchemaBuilder field(String fieldName, Schema fieldSchema) {
      builder.field(fieldName, fieldSchema);
      return this;
    }

    @Override
    public Schema keySchema() {
      return builder.keySchema();
    }

    @Override
    public Schema valueSchema() {
      return builder.valueSchema();
    }

    @Override
    public Schema build() {
      // Don't create a ConnectSchema
      return this;
    }

    @Override
    public Schema schema() {
      // Don't create a ConnectSchema
      return this;
    }
  }

  private interface LogicalTypeConverter {
    Object convert(Schema schema, Object value);
  }

  /**
   * Class that holds the context for performing {@code toConnectSchema}
   */
  private static class ToConnectContext {
    private final Map<String, SchemaBuilder> messageToStructMap;

    public ToConnectContext() {
      this.messageToStructMap = new HashMap<>();
    }

    public SchemaBuilder get(String messageName) {
      return messageToStructMap.get(messageName);
    }

    public void put(String messageName, SchemaBuilder builder) {
      messageToStructMap.put(messageName, builder);
    }
  }

  /**
   * Class that holds the context for performing {@code fromConnectSchema}
   */
  private static class FromConnectContext {
    private final Set<String> structNames;

    public FromConnectContext() {
      this.structNames = new HashSet<>();
    }

    public boolean contains(String structName) {
      return structName != null ? structNames.contains(structName) : false;
    }

    public void add(String structName) {
      if (structName != null) {
        structNames.add(structName);
      }
    }
  }
}
