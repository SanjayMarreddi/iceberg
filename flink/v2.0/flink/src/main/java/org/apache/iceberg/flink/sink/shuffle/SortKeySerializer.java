/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.flink.sink.shuffle;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.TypeSerializerSchemaCompatibility;
import org.apache.flink.api.common.typeutils.TypeSerializerSnapshot;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataOutputView;
import org.apache.flink.util.StringUtils;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.SortField;
import org.apache.iceberg.SortKey;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.SortOrderParser;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.types.CheckCompatibility;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types;

class SortKeySerializer extends TypeSerializer<SortKey> {
  private final Schema schema;
  private final SortOrder sortOrder;
  private final int size;
  private final Types.NestedField[] transformedFields;

  private int version;

  private transient SortKey sortKey;

  SortKeySerializer(Schema schema, SortOrder sortOrder, int version) {
    this.version = version;
    this.schema = schema;
    this.sortOrder = sortOrder;
    this.size = sortOrder.fields().size();

    this.transformedFields = new Types.NestedField[size];
    for (int i = 0; i < size; ++i) {
      SortField sortField = sortOrder.fields().get(i);
      Types.NestedField sourceField = schema.findField(sortField.sourceId());
      Type resultType = sortField.transform().getResultType(sourceField.type());
      Types.NestedField transformedField =
          Types.NestedField.from(sourceField).ofType(resultType).build();
      transformedFields[i] = transformedField;
    }
  }

  SortKeySerializer(Schema schema, SortOrder sortOrder) {
    this(schema, sortOrder, SortKeySerializerSnapshot.CURRENT_VERSION);
  }

  private SortKey lazySortKey() {
    if (sortKey == null) {
      this.sortKey = new SortKey(schema, sortOrder);
    }

    return sortKey;
  }

  public int getLatestVersion() {
    return snapshotConfiguration().getCurrentVersion();
  }

  public void restoreToLatestVersion() {
    this.version = snapshotConfiguration().getCurrentVersion();
  }

  public void setVersion(int version) {
    this.version = version;
  }

  @Override
  public boolean isImmutableType() {
    return false;
  }

  @Override
  public TypeSerializer<SortKey> duplicate() {
    return new SortKeySerializer(schema, sortOrder);
  }

  @Override
  public SortKey createInstance() {
    return new SortKey(schema, sortOrder);
  }

  @Override
  public SortKey copy(SortKey from) {
    return from.copy();
  }

  @Override
  public SortKey copy(SortKey from, SortKey reuse) {
    // no benefit of reuse
    return copy(from);
  }

  @Override
  public int getLength() {
    return -1;
  }

  @Override
  public void serialize(SortKey record, DataOutputView target) throws IOException {
    Preconditions.checkArgument(
        record.size() == size,
        "Invalid size of the sort key object: %s. Expected %s",
        record.size(),
        size);
    for (int i = 0; i < size; ++i) {
      int fieldId = transformedFields[i].fieldId();
      Type.TypeID typeId = transformedFields[i].type().typeId();
      if (version > 1) {
        Object value = record.get(i, Object.class);
        if (value == null) {
          target.writeBoolean(true);
          continue;
        } else {
          target.writeBoolean(false);
        }
      }

      switch (typeId) {
        case BOOLEAN:
          target.writeBoolean(record.get(i, Boolean.class));
          break;
        case INTEGER:
        case DATE:
          target.writeInt(record.get(i, Integer.class));
          break;
        case LONG:
        case TIME:
        case TIMESTAMP:
          target.writeLong(record.get(i, Long.class));
          break;
        case FLOAT:
          target.writeFloat(record.get(i, Float.class));
          break;
        case DOUBLE:
          target.writeDouble(record.get(i, Double.class));
          break;
        case STRING:
          target.writeUTF(record.get(i, CharSequence.class).toString());
          break;
        case UUID:
          UUID uuid = record.get(i, UUID.class);
          target.writeLong(uuid.getMostSignificantBits());
          target.writeLong(uuid.getLeastSignificantBits());
          break;
        case FIXED:
        case BINARY:
          byte[] bytes = record.get(i, ByteBuffer.class).array();
          target.writeInt(bytes.length);
          target.write(bytes);
          break;
        case DECIMAL:
          BigDecimal decimal = record.get(i, BigDecimal.class);
          byte[] decimalBytes = decimal.unscaledValue().toByteArray();
          target.writeInt(decimalBytes.length);
          target.write(decimalBytes);
          target.writeInt(decimal.scale());
          break;
        case STRUCT:
        case MAP:
        case LIST:
        default:
          // SortKey transformation is a flattened struct without list and map
          throw new UnsupportedOperationException(
              String.format(
                  Locale.ROOT, "Field %d has unsupported field type: %s", fieldId, typeId));
      }
    }
  }

  @Override
  public SortKey deserialize(DataInputView source) throws IOException {
    // copying is a little faster than constructing a new SortKey object
    SortKey deserialized = lazySortKey().copy();
    deserialize(deserialized, source);
    return deserialized;
  }

  @Override
  public SortKey deserialize(SortKey reuse, DataInputView source) throws IOException {
    Preconditions.checkArgument(
        reuse.size() == size,
        "Invalid size of the sort key object: %s. Expected %s",
        reuse.size(),
        size);
    for (int i = 0; i < size; ++i) {
      if (version > 1) {
        boolean isNull = source.readBoolean();
        if (isNull) {
          reuse.set(i, null);
          continue;
        }
      }

      int fieldId = transformedFields[i].fieldId();
      Type.TypeID typeId = transformedFields[i].type().typeId();
      switch (typeId) {
        case BOOLEAN:
          reuse.set(i, source.readBoolean());
          break;
        case INTEGER:
        case DATE:
          reuse.set(i, source.readInt());
          break;
        case LONG:
        case TIME:
        case TIMESTAMP:
          reuse.set(i, source.readLong());
          break;
        case FLOAT:
          reuse.set(i, source.readFloat());
          break;
        case DOUBLE:
          reuse.set(i, source.readDouble());
          break;
        case STRING:
          reuse.set(i, source.readUTF());
          break;
        case UUID:
          long mostSignificantBits = source.readLong();
          long leastSignificantBits = source.readLong();
          reuse.set(i, new UUID(mostSignificantBits, leastSignificantBits));
          break;
        case FIXED:
        case BINARY:
          byte[] bytes = new byte[source.readInt()];
          source.read(bytes);
          reuse.set(i, ByteBuffer.wrap(bytes));
          break;
        case DECIMAL:
          byte[] unscaledBytes = new byte[source.readInt()];
          source.read(unscaledBytes);
          int scale = source.readInt();
          BigDecimal decimal = new BigDecimal(new BigInteger(unscaledBytes), scale);
          reuse.set(i, decimal);
          break;
        case STRUCT:
        case MAP:
        case LIST:
        default:
          // SortKey transformation is a flattened struct without list and map
          throw new UnsupportedOperationException(
              String.format(
                  Locale.ROOT, "Field %d has unsupported field type: %s", fieldId, typeId));
      }
    }

    return reuse;
  }

  @Override
  public void copy(DataInputView source, DataOutputView target) throws IOException {
    // no optimization here
    serialize(deserialize(source), target);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof SortKeySerializer)) {
      return false;
    }

    SortKeySerializer other = (SortKeySerializer) obj;
    return Objects.equals(schema.asStruct(), other.schema.asStruct())
        && Objects.equals(sortOrder, other.sortOrder);
  }

  @Override
  public int hashCode() {
    return schema.asStruct().hashCode() * 31 + sortOrder.hashCode();
  }

  @Override
  public TypeSerializerSnapshot<SortKey> snapshotConfiguration() {
    return new SortKeySerializerSnapshot(schema, sortOrder);
  }

  public static class SortKeySerializerSnapshot implements TypeSerializerSnapshot<SortKey> {
    private static final int CURRENT_VERSION = 2;

    private Schema schema;
    private SortOrder sortOrder;

    private int version = CURRENT_VERSION;

    /** Constructor for read instantiation. */
    @SuppressWarnings({"unused", "checkstyle:RedundantModifier"})
    public SortKeySerializerSnapshot() {
      // this constructor is used when restoring from a checkpoint.
    }

    @SuppressWarnings("checkstyle:RedundantModifier")
    public SortKeySerializerSnapshot(Schema schema, SortOrder sortOrder) {
      this.schema = schema;
      this.sortOrder = sortOrder;
    }

    @Override
    public int getCurrentVersion() {
      return CURRENT_VERSION;
    }

    @Override
    public void writeSnapshot(DataOutputView out) throws IOException {
      Preconditions.checkState(schema != null, "Invalid schema: null");
      Preconditions.checkState(sortOrder != null, "Invalid sort order: null");

      StringUtils.writeString(SchemaParser.toJson(schema), out);
      StringUtils.writeString(SortOrderParser.toJson(sortOrder), out);
    }

    @Override
    public void readSnapshot(int readVersion, DataInputView in, ClassLoader userCodeClassLoader)
        throws IOException {
      switch (readVersion) {
        case 1:
          read(in);
          this.version = 1;
          break;
        case 2:
          read(in);
          break;
        default:
          throw new IllegalArgumentException("Unknown read version: " + readVersion);
      }
    }

    @Override
    public TypeSerializerSchemaCompatibility<SortKey> resolveSchemaCompatibility(
        TypeSerializerSnapshot<SortKey> oldSerializerSnapshot) {
      if (!(oldSerializerSnapshot instanceof SortKeySerializerSnapshot)) {
        return TypeSerializerSchemaCompatibility.incompatible();
      }

      if (oldSerializerSnapshot.getCurrentVersion() == 1 && this.getCurrentVersion() == 2) {
        return TypeSerializerSchemaCompatibility.compatibleAfterMigration();
      }

      // Sort order should be identical
      SortKeySerializerSnapshot oldSnapshot = (SortKeySerializerSnapshot) oldSerializerSnapshot;
      if (!sortOrder.sameOrder(oldSnapshot.sortOrder)) {
        return TypeSerializerSchemaCompatibility.incompatible();
      }

      Set<Integer> sortFieldIds =
          sortOrder.fields().stream().map(SortField::sourceId).collect(Collectors.toSet());
      // only care about the schema related to sort fields
      Schema sortSchema = TypeUtil.project(schema, sortFieldIds);
      Schema oldSortSchema = TypeUtil.project(oldSnapshot.schema, sortFieldIds);

      List<String> compatibilityErrors =
          CheckCompatibility.writeCompatibilityErrors(sortSchema, oldSortSchema);
      if (compatibilityErrors.isEmpty()) {
        return TypeSerializerSchemaCompatibility.compatibleAsIs();
      }

      return TypeSerializerSchemaCompatibility.incompatible();
    }

    @Override
    public TypeSerializer<SortKey> restoreSerializer() {
      Preconditions.checkState(schema != null, "Invalid schema: null");
      Preconditions.checkState(sortOrder != null, "Invalid sort order: null");
      return new SortKeySerializer(schema, sortOrder, version);
    }

    private void read(DataInputView in) throws IOException {
      String schemaJson = StringUtils.readString(in);
      String sortOrderJson = StringUtils.readString(in);
      this.schema = SchemaParser.fromJson(schemaJson);
      this.sortOrder = SortOrderParser.fromJson(sortOrderJson).bind(schema);
    }
  }
}
