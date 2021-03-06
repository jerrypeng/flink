/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.api.scala.typeutils

import org.apache.flink.api.common.ExecutionConfig
import org.apache.flink.api.common.typeinfo.{AtomicType, TypeInformation}
import org.apache.flink.api.common.typeutils.{TypeComparator, TypeSerializer}

import scala.collection.JavaConverters._

/**
 * TypeInformation for [[Enumeration]] values.
 */
class EnumValueTypeInfo[E <: Enumeration](enum: E, clazz: Class[E#Value])
  extends TypeInformation[E#Value] with AtomicType[E#Value] {

  type T = E#Value

  override def isBasicType: Boolean = false
  override def isTupleType: Boolean = false
  override def isKeyType: Boolean = true
  override def getTotalFields: Int = 1
  override def getArity: Int = 1
  override def getTypeClass = clazz
  override def getGenericParameters = List.empty[TypeInformation[_]].asJava


  def createSerializer(executionConfig: ExecutionConfig): TypeSerializer[T] = {
    new EnumValueSerializer[E](enum)
  }

  override def createComparator(ascOrder: Boolean, config: ExecutionConfig): TypeComparator[T] = {
    new EnumValueComparator[E](ascOrder)
  }

  override def toString = clazz.getCanonicalName
}
