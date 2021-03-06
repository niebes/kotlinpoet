/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:JvmName("WildcardTypeNames")

package com.squareup.kotlinpoet

import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import javax.lang.model.element.TypeParameterElement
import kotlin.DeprecationLevel.WARNING
import kotlin.reflect.KClass

public class WildcardTypeName private constructor(
  outTypes: List<TypeName>,
  inTypes: List<TypeName>,
  nullable: Boolean = false,
  annotations: List<AnnotationSpec> = emptyList(),
  tags: Map<KClass<*>, Any> = emptyMap()
) : TypeName(nullable, annotations, TagMap(tags)) {
  public val outTypes: List<TypeName> = outTypes.toImmutableList()
  public val inTypes: List<TypeName> = inTypes.toImmutableList()

  init {
    require(this.outTypes.size == 1) { "unexpected out types: $outTypes" }
  }

  override fun copy(
    nullable: Boolean,
    annotations: List<AnnotationSpec>,
    tags: Map<KClass<*>, Any>
  ): WildcardTypeName {
    return WildcardTypeName(outTypes, inTypes, nullable, annotations, tags)
  }

  override fun emit(out: CodeWriter): CodeWriter {
    return when {
      inTypes.size == 1 -> out.emitCode("in %T", inTypes[0])
      outTypes == STAR.outTypes -> out.emit("*")
      else -> out.emitCode("out %T", outTypes[0])
    }
  }

  public companion object {
    /**
     * Returns a type that represents an unknown type that produces `outType`. For example, if
     * `outType` is `CharSequence`, this returns `out CharSequence`. If `outType` is `Any?`, this
     * returns `*`, which is shorthand for `out Any?`.
     */
    @JvmStatic public fun producerOf(outType: TypeName): WildcardTypeName =
      WildcardTypeName(listOf(outType), emptyList())

    @JvmStatic public fun producerOf(outType: Type): WildcardTypeName =
      producerOf(outType.asTypeName())

    @JvmStatic public fun producerOf(outType: KClass<*>): WildcardTypeName =
      producerOf(outType.asTypeName())

    /**
     * Returns a type that represents an unknown type that consumes `inType`. For example, if
     * `inType` is `String`, this returns `in String`.
     */
    @JvmStatic public fun consumerOf(inType: TypeName): WildcardTypeName =
      WildcardTypeName(listOf(ANY), listOf(inType))

    @JvmStatic public fun consumerOf(inType: Type): WildcardTypeName =
      consumerOf(inType.asTypeName())

    @JvmStatic public fun consumerOf(inType: KClass<*>): WildcardTypeName =
      consumerOf(inType.asTypeName())

    internal fun get(
      mirror: javax.lang.model.type.WildcardType,
      typeVariables: Map<TypeParameterElement, TypeVariableName>
    ): TypeName {
      val outType = mirror.extendsBound
      if (outType == null) {
        val inType = mirror.superBound
        return if (inType == null) {
          STAR
        } else {
          consumerOf(get(inType, typeVariables))
        }
      } else {
        return producerOf(get(outType, typeVariables))
      }
    }

    internal fun get(
      wildcardName: WildcardType,
      map: MutableMap<Type, TypeVariableName>
    ): TypeName {
      return WildcardTypeName(
        wildcardName.upperBounds.map { get(it, map = map) },
        wildcardName.lowerBounds.map { get(it, map = map) }
      )
    }
  }
}

@Deprecated(
  message = "Mirror APIs don't give complete information on Kotlin types. Consider using" +
    " the kotlinpoet-metadata APIs instead.",
  level = WARNING
)
@JvmName("get")
public fun javax.lang.model.type.WildcardType.asWildcardTypeName(): TypeName =
  WildcardTypeName.get(this, mutableMapOf())

@JvmName("get")
public fun WildcardType.asWildcardTypeName(): TypeName =
  WildcardTypeName.get(this, mutableMapOf())
