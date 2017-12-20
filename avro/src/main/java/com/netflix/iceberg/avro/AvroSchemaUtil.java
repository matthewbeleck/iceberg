/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.iceberg.avro;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.netflix.iceberg.types.Type;
import com.netflix.iceberg.types.TypeUtil;
import com.netflix.iceberg.types.Types;
import org.apache.avro.Schema;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.avro.Schema.Type.UNION;

public class AvroSchemaUtil {
  public static final String FIELD_ID_PROP = "field-id";
  public static final String KEY_ID_PROP = "key-id";
  public static final String VALUE_ID_PROP = "value-id";
  public static final String ELEMENT_ID_PROP = "element-id";
  public static final String ADJUST_TO_UTC_PROP = "adjust-to-utc";

  private static final Schema NULL = Schema.create(Schema.Type.NULL);

  public static Schema convert(com.netflix.iceberg.Schema schema,
                               String tableName) {
    return convert(schema, ImmutableMap.of(schema.asStruct(), tableName));
  }

  public static Schema convert(com.netflix.iceberg.Schema schema,
                               Map<Types.StructType, String> names) {
    return TypeUtil.visit(schema, new TypeToSchema(names));
  }

  public static Schema convert(Type type) {
    return convert(type, ImmutableMap.of());
  }

  public static Schema convert(Types.StructType type, String name) {
    return convert(type, ImmutableMap.of(type, name));
  }

  public static Schema convert(Type type, Map<Types.StructType, String> names) {
    return TypeUtil.visit(type, new TypeToSchema(names));
  }

  public static Type convert(Schema schema) {
    return AvroSchemaVisitor.visit(schema, new SchemaToType(schema));
  }

  public static Map<Type, Schema> convertTypes(Types.StructType type, String name) {
    TypeToSchema converter = new TypeToSchema(ImmutableMap.of(type, name));
    TypeUtil.visit(type, converter);
    return ImmutableMap.copyOf(converter.getConversionMap());
  }

  public static Schema pruneColumns(Schema schema, Set<Integer> selectedIds) {
    return new PruneColumns(selectedIds).rootSchema(schema);
  }

  public static Schema buildAvroProjection(Schema schema, com.netflix.iceberg.Schema expected,
                                           Map<String, String> renames) {
    return AvroCustomOrderSchemaVisitor.visit(schema, new BuildAvroProjection(expected, renames));
  }

  static boolean isOptionSchema(Schema schema) {
    if (schema.getType() == UNION && schema.getTypes().size() == 2) {
      if (schema.getTypes().get(0).getType() == Schema.Type.NULL) {
        return true;
      } else if (schema.getTypes().get(1).getType() == Schema.Type.NULL) {
        return true;
      }
    }
    return false;
  }

  static Schema toOption(Schema schema) {
    if (schema.getType() == UNION) {
      Preconditions.checkArgument(isOptionSchema(schema),
          "Union schemas are not supported: " + schema);
      return schema;
    } else {
      return Schema.createUnion(NULL, schema);
    }
  }

  static Schema fromOption(Schema schema) {
    Preconditions.checkArgument(schema.getType() == UNION,
        "Expected union schema but was passed: {}", schema);
    Preconditions.checkArgument(schema.getTypes().size() == 2,
        "Expected optional schema, but was passed: {}", schema);
    if (schema.getTypes().get(0).getType() == Schema.Type.NULL) {
      return schema.getTypes().get(1);
    } else {
      return schema.getTypes().get(0);
    }
  }

  static Schema fromOptions(List<Schema> options) {
    Preconditions.checkArgument(options.size() == 2,
        "Expected two schemas, but was passed: {} options", options.size());
    if (options.get(0).getType() == Schema.Type.NULL) {
      return options.get(1);
    } else {
      return options.get(0);
    }
  }

  private static int getId(Schema schema, String propertyName) {
    if (schema.getType() == UNION) {
      return getId(fromOption(schema), propertyName);
    }

    Object id = schema.getObjectProp(propertyName);
    Preconditions.checkNotNull(id, "Missing expected '%s' property", propertyName);

    return toInt(id);
  }

  static int getKeyId(Schema schema) {
    return getId(schema, KEY_ID_PROP);
  }

  static int getValueId(Schema schema) {
    return getId(schema, VALUE_ID_PROP);
  }

  static int getElementId(Schema schema) {
    return getId(schema, ELEMENT_ID_PROP);
  }

  static int getId(Schema.Field field) {
    Object id = field.getObjectProp(FIELD_ID_PROP);
    Preconditions.checkNotNull(id, "Missing expected '%s' property", FIELD_ID_PROP);

    return toInt(id);
  }

  private static int toInt(Object value) {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    } else if (value instanceof String) {
      return Integer.parseInt((String) value);
    }

    throw new UnsupportedOperationException("Cannot coerce value to int: " + value);
  }

  static Schema copyRecord(Schema record, List<Schema.Field> newFields, String newName) {
    Schema copy;
    if (newName != null) {
      copy = Schema.createRecord(newName, record.getDoc(), null, record.isError(), newFields);
      // the namespace is defaulted to the record's namespace if it is null, which causes renames
      // without the namespace to fail. using "" instead of null changes this behavior to match the
      // original schema.
      copy.addAlias(record.getName(), record.getNamespace() == null ? "" : record.getNamespace());
    } else {
      copy = Schema.createRecord(record.getName(),
          record.getDoc(), record.getNamespace(), record.isError(), newFields);
    }

    for (Map.Entry<String, Object> prop : record.getObjectProps().entrySet()) {
      copy.addProp(prop.getKey(), prop.getValue());
    }

    return copy;
  }

  static Schema.Field copyField(Schema.Field field, Schema newSchema, String newName) {
    Schema.Field copy = new Schema.Field(newName,
        newSchema, field.doc(), field.defaultVal(), field.order());

    for (Map.Entry<String, Object> prop : field.getObjectProps().entrySet()) {
      copy.addProp(prop.getKey(), prop.getValue());
    }

    if (!newName.equals(field.name())) {
      copy.addAlias(field.name());
    }

    return copy;
  }
}