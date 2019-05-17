package com.sksamuel.avro4s

import java.nio.ByteBuffer
import java.sql.Timestamp
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime}
import java.util.UUID

import magnolia.{CaseClass, Magnolia, SealedTrait}
import org.apache.avro.{JsonProperties, LogicalTypes, Schema, SchemaBuilder}

import scala.language.experimental.macros
import scala.math.BigDecimal.RoundingMode.{RoundingMode, UNNECESSARY}
import scala.reflect.ClassTag
import scala.reflect.runtime.currentMirror
import scala.reflect.runtime.universe._
import scala.tools.reflect.ToolBox
import scala.util.control.NonFatal


/**
  * A [[SchemaFor]] generates an Avro Schema for a Scala or Java type.
  *
  * For example, a String SchemaFor could return an instance of Schema.Type.STRING
  * or Schema.Type.FIXED depending on the type required for Strings.
  */
trait SchemaFor[T] extends Serializable {
  self =>

  def schema(implicit namingStrategy: NamingStrategy = DefaultNamingStrategy): Schema

  /**
    * Creates a SchemaFor[U] by applying a function Schema => Schema
    * to the schema generated by this instance.
    */
  def map[U](fn: Schema => Schema): SchemaFor[U] = new SchemaFor[U] {
    override def schema(implicit namingStrategy: NamingStrategy): Schema = fn(self.schema)
  }
}

case class ScalePrecisionRoundingMode(scale: Int, precision: Int, roundingMode: RoundingMode)

object ScalePrecisionRoundingMode {
  implicit val default = ScalePrecisionRoundingMode(2, 8, UNNECESSARY)
}

object SchemaFor {

  import scala.collection.JavaConverters._

  type Typeclass[T] = SchemaFor[T]

  implicit def gen[T]: Typeclass[T] = macro Magnolia.gen[T]

  def apply[T](implicit schemaFor: SchemaFor[T]): SchemaFor[T] = schemaFor

  /**
    * Creates a [[SchemaFor]] that always returns the given constant value.
    */
  def const[T](_schema: Schema) = new SchemaFor[T] {
    override def schema(implicit namingStrategy: NamingStrategy) = _schema
  }

  implicit val StringSchemaFor: SchemaFor[String] = const(SchemaBuilder.builder.stringType)
  implicit val LongSchemaFor: SchemaFor[Long] = const(SchemaBuilder.builder.longType)
  implicit val IntSchemaFor: SchemaFor[Int] = const(SchemaBuilder.builder.intType)
  implicit val DoubleSchemaFor: SchemaFor[Double] = const(SchemaBuilder.builder.doubleType)
  implicit val FloatSchemaFor: SchemaFor[Float] = const(SchemaBuilder.builder.floatType)
  implicit val BooleanSchemaFor: SchemaFor[Boolean] = const(SchemaBuilder.builder.booleanType)
  implicit val ByteArraySchemaFor: SchemaFor[Array[Byte]] = const(SchemaBuilder.builder.bytesType)
  implicit val ByteSeqSchemaFor: SchemaFor[Seq[Byte]] = const(SchemaBuilder.builder.bytesType)
  implicit val ByteListSchemaFor: SchemaFor[List[Byte]] = const(SchemaBuilder.builder.bytesType)
  implicit val ByteVectorSchemaFor: SchemaFor[Vector[Byte]] = const(SchemaBuilder.builder.bytesType)
  implicit val ByteBufferSchemaFor: SchemaFor[ByteBuffer] = const(SchemaBuilder.builder.bytesType)
  implicit val ShortSchemaFor: SchemaFor[Short] = const(IntSchemaFor.schema(DefaultNamingStrategy))
  implicit val ByteSchemaFor: SchemaFor[Byte] = const(IntSchemaFor.schema(DefaultNamingStrategy))

  implicit object UUIDSchemaFor extends SchemaFor[UUID] {
    override def schema(implicit namingStrategy: NamingStrategy) = LogicalTypes.uuid().addToSchema(SchemaBuilder.builder.stringType)
  }

  implicit def mapSchemaFor[V](implicit schemaFor: SchemaFor[V]): SchemaFor[Map[String, V]] = {
    new SchemaFor[Map[String, V]] {
      override def schema(implicit namingStrategy: NamingStrategy) = SchemaBuilder.map().values(schemaFor.schema)
    }
  }

  implicit def bigDecimalFor(implicit sp: ScalePrecisionRoundingMode = ScalePrecisionRoundingMode.default): SchemaFor[BigDecimal] = new SchemaFor[BigDecimal] {
    override def schema(implicit namingStrategy: NamingStrategy) = LogicalTypes.decimal(sp.precision, sp.scale).addToSchema(SchemaBuilder.builder.bytesType)
  }

  implicit def eitherSchemaFor[A, B](implicit leftFor: SchemaFor[A], rightFor: SchemaFor[B]): SchemaFor[Either[A, B]] = {
    new SchemaFor[Either[A, B]] {
      override def schema(implicit namingStrategy: NamingStrategy) = SchemaHelper.createSafeUnion(leftFor.schema, rightFor.schema)
    }
  }

  implicit def optionSchemaFor[T](implicit schemaFor: SchemaFor[T]): SchemaFor[Option[T]] = new SchemaFor[Option[T]] {
    override def schema(implicit namingStrategy: NamingStrategy) = {
      val elementSchema = schemaFor.schema
      val nullSchema = SchemaBuilder.builder().nullType()
      SchemaHelper.createSafeUnion(elementSchema, nullSchema)
    }
  }

  implicit def arraySchemaFor[S](implicit schemaFor: SchemaFor[S]): SchemaFor[Array[S]] = {
    new SchemaFor[Array[S]] {
      override def schema(implicit namingStrategy: NamingStrategy): Schema = Schema.createArray(schemaFor.schema)
    }
  }

  implicit def listSchemaFor[S](implicit schemaFor: SchemaFor[S]): SchemaFor[List[S]] = {
    new SchemaFor[List[S]] {
      override def schema(implicit namingStrategy: NamingStrategy): Schema = Schema.createArray(schemaFor.schema)
    }
  }

  implicit def setSchemaFor[S](implicit schemaFor: SchemaFor[S]): SchemaFor[Set[S]] = {
    new SchemaFor[Set[S]] {
      override def schema(implicit namingStrategy: NamingStrategy): Schema = Schema.createArray(schemaFor.schema)
    }
  }

  implicit def vectorSchemaFor[S](implicit schemaFor: SchemaFor[S]): SchemaFor[Vector[S]] = {
    new SchemaFor[Vector[S]] {
      override def schema(implicit namingStrategy: NamingStrategy): Schema = Schema.createArray(schemaFor.schema)
    }
  }

  implicit def seqSchemaFor[S](implicit schemaFor: SchemaFor[S]): SchemaFor[Seq[S]] = {
    new SchemaFor[Seq[S]] {
      override def schema(implicit namingStrategy: NamingStrategy): Schema = Schema.createArray(schemaFor.schema)
    }
  }

  implicit def iterableSchemaFor[S](implicit schemaFor: SchemaFor[S]): SchemaFor[Iterable[S]] = {
    new SchemaFor[Iterable[S]] {
      override def schema(implicit namingStrategy: NamingStrategy): Schema = Schema.createArray(schemaFor.schema)
    }
  }

  implicit object TimestampSchemaFor extends SchemaFor[Timestamp] {
    override def schema(implicit namingStrategy: NamingStrategy) = LogicalTypes.timestampMillis().addToSchema(SchemaBuilder.builder.longType)
  }

  implicit object LocalTimeSchemaFor extends SchemaFor[LocalTime] {
    override def schema(implicit namingStrategy: NamingStrategy) = LogicalTypes.timeMillis().addToSchema(SchemaBuilder.builder.intType)
  }

  implicit object LocalDateSchemaFor extends SchemaFor[LocalDate] {
    override def schema(implicit namingStrategy: NamingStrategy) = LogicalTypes.date().addToSchema(SchemaBuilder.builder.intType)
  }

  implicit object LocalDateTimeSchemaFor extends SchemaFor[LocalDateTime] {
    override def schema(implicit namingStrategy: NamingStrategy) = LogicalTypes.timestampMillis().addToSchema(SchemaBuilder.builder.longType)
  }

  implicit object DateSchemaFor extends SchemaFor[java.sql.Date] {
    override def schema(implicit namingStrategy: NamingStrategy) = LogicalTypes.date().addToSchema(SchemaBuilder.builder.intType)
  }

  implicit object InstantSchemaFor extends SchemaFor[Instant] {
    override def schema(implicit namingStrategy: NamingStrategy) = LogicalTypes.timestampMillis().addToSchema(SchemaBuilder.builder.longType)
  }

  implicit def javaEnumSchemaFor[E <: Enum[_]](implicit tag: ClassTag[E]): SchemaFor[E] = new SchemaFor[E] {
    override def schema(implicit namingStrategy: NamingStrategy): Schema = {

      val annos = tag.runtimeClass.getAnnotations.toList.map { a =>
        val tb = currentMirror.mkToolBox()

        val args = tb.compile(tb.parse(a.toString)).apply() match {
          case c: AvroFieldReflection => c.getAllFields.map { case (k, v) => (k, v.toString) }
          case _ => Map.empty[String, String]
        }
        Anno(a.annotationType.getClass.getName, args)
      }

      val extractor = new AnnotationExtractors(annos)

      val name = tag.runtimeClass.getSimpleName
      val namespace = extractor.namespace.getOrElse(tag.runtimeClass.getPackage.getName)
      val symbols = tag.runtimeClass.getEnumConstants.map(_.toString)

      SchemaBuilder.enumeration(name).namespace(namespace).symbols(symbols: _*)
    }
  }


  /**
    * Builds an Avro Field with the field's Schema provided by an
    * implicit instance of [[SchemaFor]]. There must be a instance of this
    * typeclass in scope for any type we want to support in avro4s.
    *
    * Users can add their own mappings for types by implementing a [[SchemaFor]]
    * instance for that type.
    *
    * @param label   the name of the field as defined in the case class
    * @param annos   the name of the package that contains the case class definition
    * @param default an instance of the Default ADT which contains an avro compatible default value
    *                if such a default applies to this field
    */
  private def buildField[B](label: String,
                            namespace: String,
                            annos: Seq[Any],
                            schemaFor: SchemaFor[B],
                            default: Option[B],
                            namingStrategy: NamingStrategy): Schema.Field = {

    val extractor = new AnnotationExtractors(annos)
    val doc = extractor.doc.orNull
    val aliases = extractor.aliases
    val props = extractor.props

    // the name could have been overriden with @AvroName, and then must be encoded with the naming strategy
    val name = extractor.name.fold(namingStrategy.to(label))(namingStrategy.to)

    // the default value may be none, in which case it was not defined, or Some(null), in which case it was defined
    // and set to null, or something else, in which case it's a non null value
    val encodedDefault: AnyRef = default match {
      case None => null
      case Some(null) => JsonProperties.NULL_VALUE
      case Some(other) => DefaultResolver(other, schemaFor.schema(namingStrategy))
    }

    // if we have annotated with @AvroFixed then we override the type and change it to a Fixed schema
    // if someone puts @AvroFixed on a complex type, it makes no sense, but that's their cross to bear
    val schema = extractor.fixed.fold(schemaFor.schema(namingStrategy)) { size =>
      SchemaBuilder.fixed(name).doc(doc).namespace(extractor.namespace.getOrElse(namespace)).size(size)
    }

    // for a union the type that has a default must be first
    // if there is no default then we'll move null to head (if present)
    val schemaWithOrderedUnion = (schema.getType, encodedDefault) match {
      case (Schema.Type.UNION, null) => SchemaHelper.moveDefaultToHead(schema, null)
      case (Schema.Type.UNION, JsonProperties.NULL_VALUE) => SchemaHelper.moveDefaultToHead(schema, null)
      case (Schema.Type.UNION, defaultValue) => SchemaHelper.moveDefaultToHead(schema, defaultValue)
      case _ => schema
    }

    // the field can override the namespace if the Namespace annotation is present on the field
    // we may have annotated our field with @AvroNamespace so this namespace should be applied
    // to any schemas we have generated for this field
    val schemaWithResolvedNamespace = extractor.namespace.map(overrideNamespace(schemaWithOrderedUnion, _)).getOrElse(schemaWithOrderedUnion)

    val field = new Schema.Field(name, schemaWithResolvedNamespace, doc, encodedDefault)
    props.foreach { case (k, v) => field.addProp(k, v: AnyRef) }
    aliases.foreach(field.addAlias)
    field
  }

  def combine[T](klass: CaseClass[Typeclass, T]): SchemaFor[T] = {

    val extractor = new AnnotationExtractors(klass.annotations)
    val doc = extractor.doc.orNull
    val aliases = extractor.aliases
    val props = extractor.props

    val namer = Namer(klass.typeName, klass.annotations)
    val namespace = namer.namespace
    val name = namer.name

    // if the class is a value type, then we need to use the schema for the single field inside the type
    // in other words, if we have `case class Foo(str:String)` then this just acts like a string
    // if we have a value type AND @AvroFixed is present on the class, then we simply return a schema of type fixed
    if (klass.isValueClass) {
      new SchemaFor[T] {
        val param = klass.parameters.head
        override def schema(implicit namingStrategy: NamingStrategy): Schema = {
          extractor.fixed match {
            case Some(size) =>
              val builder = SchemaBuilder.fixed(name).doc(doc).namespace(namespace).aliases(aliases: _*)
              props.foreach { case (k, v) => builder.prop(k, v) }
              builder.size(size)
            case None => param.typeclass.schema
          }
        }
      }

    } else {
      new SchemaFor[T] {
        override def schema(implicit namingStrategy: NamingStrategy) = {

          val fields = klass.parameters.map { param =>
            buildField(param.label, namespace, param.annotations, param.typeclass, param.default, namingStrategy)
          }

          val record = Schema.createRecord(name, doc, namespace, false)
          aliases.foreach(record.addAlias)
          props.foreach { case (k, v) => record.addProp(k: String, v: AnyRef) }
          record.setFields(fields.asJava)

          record
        }
      }
    }
  }

  def dispatch[T](ctx: SealedTrait[Typeclass, T]): SchemaFor[T] = new SchemaFor[T] {
    override def schema(implicit namingStrategy: NamingStrategy) = {

      import scala.reflect.runtime.universe

      val runtimeMirror = universe.runtimeMirror(getClass.getClassLoader)

      // if all objects then we encode as enums, otherwise as a union
      // this is a big hacky until magnolia can do it for us
      val objs = ctx.subtypes.forall { subtype =>
        try {
          // to be a case object, we need the object, but no class
          val module = runtimeMirror.staticModule(subtype.typeName.full)
          !runtimeMirror.staticClass(subtype.typeName.full).isCaseClass
        } catch {
          case NonFatal(_) => false
        }
      }

      if (objs) {
        val symbols = ctx.subtypes.map { sub =>
          val namer = Namer(sub.typeName, sub.annotations)
          namer.name
        }
        val namer = Namer(ctx.typeName, ctx.annotations)
        SchemaBuilder.enumeration(namer.name).namespace(namer.namespace).symbols(symbols: _*)
      } else {
        val schemas = ctx.subtypes.map(_.typeclass.schema)
        SchemaHelper.createSafeUnion(schemas: _*)
      }
    }
  }

  implicit def scalaEnumSchemaFor[E <: scala.Enumeration#Value](implicit tag: TypeTag[E]): SchemaFor[E] = new SchemaFor[E] {

    val typeRef = tag.tpe match {
      case t@TypeRef(_, _, _) => t
    }

    val valueType = typeOf[E]
    val pre = typeRef.pre.typeSymbol.typeSignature.members
    val syms = pre.filter { sym =>
      !sym.isMethod &&
        !sym.isType &&
        sym.typeSignature.baseType(valueType.typeSymbol) =:= valueType
    }.map { sym =>
      sym.name.decodedName.toString.trim
    }.toList.sorted

    val annos = typeRef.pre.typeSymbol.annotations.map { a =>
      val name = a.tree.tpe.typeSymbol.fullName
      val tb = currentMirror.mkToolBox()
      val args = tb.compile(tb.parse(a.toString)).apply() match {
        case c: AvroFieldReflection => c.getAllFields.map { case (k, v) => (k, v.toString) }
        case _ => Map.empty[String, String]
      }
      Anno(name, args)
    }

    val extractor = new AnnotationExtractors(annos)

    val namespace = extractor.namespace.getOrElse(typeRef.pre.typeSymbol.owner.fullName)
    val name = extractor.name.getOrElse(typeRef.pre.typeSymbol.name.decodedName.toString)

    override def schema(implicit namingStrategy: NamingStrategy) = SchemaBuilder.enumeration(name).namespace(namespace).symbols(syms: _*)
  }

  //  implicit def genCoproductSingletons[T, C <: Coproduct, L <: HList](implicit ct: ClassTag[T],
  //                                                                     tag: TypeTag[T],
  //                                                                     gen: Generic.Aux[T, C],
  //                                                                     objs: Reify.Aux[C, L],
  //                                                                     toList: ToList[L, T]): SchemaFor[T] = new SchemaFor[T] {
  //    val tpe = weakTypeTag[T]
  //    val nr = NameResolution(tpe.tpe)
  //    val symbols = toList(objs()).map(v => NameResolution(v.getClass).name)
  //
  //    override def schema: Schema = SchemaBuilder.enumeration(nr.name).namespace(nr.namespace).symbols(symbols: _*)
  //  }

  //  def applyMacroImpl[T: c.WeakTypeTag](c: whitebox.Context): c.Expr[SchemaFor[T]] = {
  //    import c.universe
  //    import c.universe._
  //
  //    val reflect = ReflectHelper(c)
  //    val tpe = weakTypeOf[T]
  //    val fullName = tpe.typeSymbol.fullName
  //    val packageName = reflect.packageName(tpe)
  //    val resolution = NameResolution(c)(tpe)
  //    val namespace = resolution.namespace
  //    val name = resolution.name
  //
  //    if (!reflect.isCaseClass(tpe)) {
  //      c.abort(c.prefix.tree.pos, s"$fullName is not a case class: This macro is only designed to handle case classes")
  //    } else if (reflect.isSealed(tpe)) {
  //      c.abort(c.prefix.tree.pos, s"$fullName is sealed: Sealed traits/classes should be handled by coproduct generic")
  //    } else if (packageName.startsWith("scala") || packageName.startsWith("java")) {
  //      c.abort(c.prefix.tree.pos, s"$fullName is a library type: Built in types should be handled by explicit typeclasses of SchemaFor and not this macro")
  //    } else if (reflect.isScalaEnum(tpe)) {
  //      c.abort(c.prefix.tree.pos, s"$fullName is a scala enum: Scala enum types should be handled by `scalaEnumSchemaFor`")
  //    } else {
  //
  //      val isValueClass = reflect.isValueClass(tpe)
  //
  //      val fields = reflect
  //        .constructorParameters(tpe)
  //        .zipWithIndex
  //        .filterNot { case ((fieldSym, _), _) => reflect.isTransientOnField(tpe, fieldSym) }
  //        .map { case ((fieldSym, fieldTpe), index) =>
  //
  //          // the simple name of the field like "x"
  //          val fieldName = fieldSym.name.decodedName.toString.trim
  //
  //          // if the field is a value type, we should include annotations defined on the value type as well
  //          val annos = if (reflect.isValueClass(fieldTpe)) {
  //            reflect.annotationsqq(fieldSym) ++ reflect.annotationsqq(fieldTpe.typeSymbol)
  //          } else {
  //            reflect.annotationsqq(fieldSym)
  //          }
  //
  //          val defswithsymbols = universe.asInstanceOf[Definitions with SymbolTable with StdNames]
  //
  //          // gets the method symbol for the default getter if it exists
  //          val defaultGetterName = defswithsymbols.nme.defaultGetterName(defswithsymbols.nme.CONSTRUCTOR, index + 1)
  //          val defaultGetterMethod = tpe.companion.member(TermName(defaultGetterName.toString))
  //
  //          // if the default getter exists then we can use it to generate the default value
  //          if (defaultGetterMethod.isMethod) {
  //            val moduleSym = tpe.typeSymbol.companion
  //            if (reflect.isMacroGenerated(fieldTpe)) {
  //              q"""{ _root_.com.sksamuel.avro4s.SchemaFor.schemaFieldWithDefault[$fieldTpe]($fieldName, $namespace, Seq(..$annos), $moduleSym.$defaultGetterMethod) }"""
  //            } else {
  //              q"""{ _root_.com.sksamuel.avro4s.SchemaFor.schemaFieldWithDefaultLazy[$fieldTpe]($fieldName, $namespace, Seq(..$annos), $moduleSym.$defaultGetterMethod) }"""
  //            }
  //          } else {
  //            if (reflect.isMacroGenerated(fieldTpe)) {
  //              q"""{ _root_.com.sksamuel.avro4s.SchemaFor.schemaFieldNoDefault[$fieldTpe]($fieldName, $namespace, Seq(..$annos)) }"""
  //            } else {
  //              q"""{ _root_.com.sksamuel.avro4s.SchemaFor.schemaFieldNoDefaultLazy[$fieldTpe]($fieldName, $namespace, Seq(..$annos)) }"""
  //            }
  //          }
  //        }
  //
  //      // these are annotations on the class itself
  //      val annos = reflect.annotationsqq(tpe.typeSymbol)
  //
  //      c.Expr[SchemaFor[T]](
  //        q"""
  //        new _root_.com.sksamuel.avro4s.SchemaFor[$tpe] {
  //          private val _schema = _root_.com.sksamuel.avro4s.SchemaFor.buildSchema($name, $namespace, Seq(..$fields), Seq(..$annos), $isValueClass)
  //          override def schema: _root_.org.apache.avro.Schema = _schema
  //        }
  //      """)
  //    }
  //  }

  //  def schemaFieldNoDefault[B](fieldName: String, namespace: String, annos: Seq[Anno])
  //                             (implicit schemaFor: SchemaFor[B], namingStrategy: NamingStrategy = DefaultNamingStrategy): Schema.Field = {
  //    buildField[B](fieldName, namespace, annos, NoDefault, schemaFor, namingStrategy)
  //  }
  //
  //  def schemaFieldNoDefaultLazy[B](fieldName: String, namespace: String, annos: Seq[Anno])
  //                                 (implicit schemaFor: Lazy[SchemaFor[B]], namingStrategy: NamingStrategy = DefaultNamingStrategy): Schema.Field = {
  //    buildField[B](fieldName, namespace, annos, NoDefault, schemaFor.value, namingStrategy)
  //  }
  //
  //  def schemaFieldWithDefault[B](fieldName: String, namespace: String, annos: Seq[Anno], default: B)
  //                               (implicit schemaFor: SchemaFor[B], encoder: Encoder[B], namingStrategy: NamingStrategy = DefaultNamingStrategy): Schema.Field = {
  //    // the default may be a scala type that avro doesn't understand, so we must turn
  //    // it into an avro compatible type by using an encoder.
  //    buildField[B](fieldName, namespace, annos, Default(encoder.encode(default, schemaFor.schema)), schemaFor, namingStrategy)
  //  }
  //
  //  def schemaFieldWithDefaultLazy[B](fieldName: String, namespace: String, annos: Seq[Anno], default: B)
  //                                   (implicit schemaFor: Lazy[SchemaFor[B]], encoder: Encoder[B], namingStrategy: NamingStrategy = DefaultNamingStrategy): Schema.Field = {
  //    // the default may be a scala type that avro doesn't understand, so we must turn
  //    // it into an avro compatible type by using an encoder.
  //    buildField[B](fieldName, namespace, annos, Default(encoder.encode(default, schemaFor.value.schema)), schemaFor.value, namingStrategy)
  //  }

  //  /**
  //    * Builds an Avro Field with the field's Schema provided by an
  //    * implicit instance of [[SchemaFor]]. There must be a instance of this
  //    * typeclass in scope for any type we want to support in avro4s.
  //    *
  //    * Users can add their own mappings for types by implementing a [[SchemaFor]]
  //    * instance for that type.
  //    *
  //    * @param fieldName the name of the field as defined in the case class
  //    * @param namespace the name of the package that contains the case class definition
  //    * @param default   an instance of the Default ADT which contains an avro compatible default value
  //    *                  if such a default applies to this field
  //    */
  //  private def buildField[B](fieldName: String,
  //                            namespace: String,
  //                            annos: Seq[Anno],
  //                            default: Default,
  //                            schemaFor: SchemaFor[B],
  //                            namingStrategy: NamingStrategy): Schema.Field = {
  //
  //    val extractor = new AnnotationExtractors(annos)
  //    val doc = extractor.doc.orNull
  //    val aliases = extractor.aliases
  //    val props = extractor.props
  //
  //    // the name could have been overriden with @AvroName, and then must be encoded with the naming strategy
  //    val name = extractor.name.fold(namingStrategy.to(fieldName))(namingStrategy.to)
  //
  //    // the special NullDefault is used when null is actually the default value.
  //    // The case of having no default at all is represented by NoDefault
  //    val encodedDefault: AnyRef = default match {
  //      case NoDefault => null
  //      case NullDefault => JsonProperties.NULL_VALUE
  //      case MethodDefault(x) => DefaultResolver(x, schemaFor.schema)
  //    }
  //
  //    // if we have annotated with @AvroFixed then we override the type and change it to a Fixed schema
  //    // if someone puts @AvroFixed on a complex type, it makes no sense, but that's their cross to bear
  //    val schema = extractor.fixed.fold(schemaFor.schema) { size =>
  //      SchemaBuilder.fixed(name).doc(doc).namespace(namespace).size(size)
  //    }
  //
  //    // for a union the type that has a default must be first
  //    // if there is no default then we'll move null to head (if present)
  //    val schemaWithOrderedUnion = (schema.getType, encodedDefault) match {
  //      case (Schema.Type.UNION, null) => SchemaHelper.moveDefaultToHead(schema, null)
  //      case (Schema.Type.UNION, JsonProperties.NULL_VALUE) => SchemaHelper.moveDefaultToHead(schema, null)
  //      case (Schema.Type.UNION, defaultValue) => SchemaHelper.moveDefaultToHead(schema, defaultValue)
  //      case _ => schema
  //    }
  //
  //    // the field can override the namespace if the Namespace annotation is present on the field
  //    // we may have annotated our field with @AvroNamespace so this namespace should be applied
  //    // to any schemas we have generated for this field
  //    val schemaWithResolvedNamespace = extractor.namespace.map(overrideNamespace(schemaWithOrderedUnion, _)).getOrElse(schemaWithOrderedUnion)
  //
  //    val field = new Schema.Field(name, schemaWithResolvedNamespace, doc, encodedDefault)
  //    props.foreach { case (k, v) => field.addProp(k, v: AnyRef) }
  //    aliases.foreach(field.addAlias)
  //    field
  //  }
  //
  //  /**
  //    * Builds a new Avro Schema.
  //    *
  //    * @param name the encoded Avro record name, taking into account
  //    *             annnotations and type parameters.
  //    */
  //  def buildSchema(name: String,
  //                  namespace: String,
  //                  fields: Seq[Schema.Field],
  //                  annotations: Seq[Anno],
  //                  valueType: Boolean): Schema = {
  //
  //    import scala.collection.JavaConverters._
  //
  //    val extractor = new AnnotationExtractors(annotations)
  //    val doc = extractor.doc.orNull
  //    val aliases = extractor.aliases
  //    val props = extractor.props
  //
  //    // if the class is a value type, then we need to use the schema for the single field of the type
  //    // if we have a value type AND @AvroFixed is present, then we return a schema of type fixed
  //    if (valueType) {
  //      val field = fields.head
  //      extractor.fixed.fold(field.schema) { size =>
  //        val builder = SchemaBuilder.fixed(name).doc(doc).namespace(namespace).aliases(aliases: _*)
  //        props.foreach { case (k, v) => builder.prop(k, v) }
  //        builder.size(size)
  //      }
  //    } else {
  //      val record = Schema.createRecord(name, doc, namespace, false)
  //      aliases.foreach(record.addAlias)
  //      props.foreach { case (k, v) => record.addProp(k: String, v: AnyRef) }
  //      record.setFields(fields.asJava)
  //      record
  //    }
  //  }

  // accepts a built avro schema, and overrides the namespace with the given namespace
  // this method just just makes a copy of the existing schema, setting the new namespace
  private def overrideNamespace(schema: Schema, namespace: String): Schema = {
    schema.getType match {
      case Schema.Type.RECORD =>
        val fields = schema.getFields.asScala.map { field =>
          new Schema.Field(field.name(), overrideNamespace(field.schema(), namespace), field.doc, field.defaultVal, field.order)
        }
        val copy = Schema.createRecord(schema.getName, schema.getDoc, namespace, schema.isError, fields.asJava)
        schema.getAliases.asScala.foreach(copy.addAlias)
        schema.getObjectProps.asScala.foreach { case (k, v) => copy.addProp(k, v) }
        copy
      case Schema.Type.UNION => Schema.createUnion(schema.getTypes.asScala.map(overrideNamespace(_, namespace)).asJava)
      case Schema.Type.ENUM => Schema.createEnum(schema.getName, schema.getDoc, namespace, schema.getEnumSymbols)
      case Schema.Type.FIXED => Schema.createFixed(schema.getName, schema.getDoc, namespace, schema.getFixedSize)
      case Schema.Type.MAP => Schema.createMap(overrideNamespace(schema.getValueType, namespace))
      case Schema.Type.ARRAY => Schema.createArray(overrideNamespace(schema.getElementType, namespace))
      case _ => schema
    }
  }

  // A coproduct is a union, or a generalised either.
  // A :+: B :+: C :+: CNil is a type that is either an A, or a B, or a C.

  // Shapeless's implementation builds up the type recursively,
  // (i.e., it's actually A :+: (B :+: (C :+: CNil)))
  // so here we define the schema for the base case of the recursion, C :+: CNil
  //  implicit def coproductBaseSchema[S](implicit basefor: SchemaFor[S]): SchemaFor[S :+: CNil] = new SchemaFor[S :+: CNil] {
  //
  //    import scala.collection.JavaConverters._
  //
  //    val base = basefor.schema
  //    val schemas = scala.util.Try(base.getTypes.asScala).getOrElse(Seq(base))
  //    override def schema(implicit namingStrategy: NamingStrategy) = Schema.createUnion(schemas.asJava)
  //  }
  //
  //  // And here we continue the recursion up.
  //  implicit def coproductSchema[S, T <: Coproduct](implicit basefor: SchemaFor[S], coproductFor: SchemaFor[T]): SchemaFor[S :+: T] = new SchemaFor[S :+: T] {
  //    val base = basefor.schema
  //    val coproduct = coproductFor.schema
  //    override def schema(implicit namingStrategy: NamingStrategy) = SchemaHelper.createSafeUnion(base, coproduct)
  //  }

  //  implicit def genCoproduct[T, C <: Coproduct](implicit gen: Generic.Aux[T, C],
  //                                               coproductFor: SchemaFor[C]): SchemaFor[T] = new SchemaFor[T] {
  //    override def schema: Schema = coproductFor.schema
  //  }

  implicit def tuple2SchemaFor[A, B](implicit a: SchemaFor[A], b: SchemaFor[B]): SchemaFor[(A, B)] = new SchemaFor[(A, B)] {
    override def schema(implicit namingStrategy: NamingStrategy): Schema =
      SchemaBuilder.record("Tuple2").namespace("scala").doc(null)
        .fields()
        .name("_1").`type`(a.schema).noDefault()
        .name("_2").`type`(b.schema).noDefault()
        .endRecord()
  }

  implicit def tuple3SchemaFor[A, B, C](implicit
                                        a: SchemaFor[A],
                                        b: SchemaFor[B],
                                        c: SchemaFor[C]): SchemaFor[(A, B, C)] = new SchemaFor[(A, B, C)] {
    override def schema(implicit namingStrategy: NamingStrategy): Schema =
      SchemaBuilder.record("Tuple3").namespace("scala").doc(null)
        .fields()
        .name("_1").`type`(a.schema).noDefault()
        .name("_2").`type`(b.schema).noDefault()
        .name("_3").`type`(c.schema).noDefault()
        .endRecord()
  }

  implicit def tuple4SchemaFor[A, B, C, D](implicit
                                           a: SchemaFor[A],
                                           b: SchemaFor[B],
                                           c: SchemaFor[C],
                                           d: SchemaFor[D]): SchemaFor[(A, B, C, D)] = new SchemaFor[(A, B, C, D)] {
    override def schema(implicit namingStrategy: NamingStrategy): Schema =
      SchemaBuilder.record("Tuple4").namespace("scala").doc(null)
        .fields()
        .name("_1").`type`(a.schema).noDefault()
        .name("_2").`type`(b.schema).noDefault()
        .name("_3").`type`(c.schema).noDefault()
        .name("_4").`type`(d.schema).noDefault()
        .endRecord()
  }

  implicit def tuple5SchemaFor[A, B, C, D, E](implicit
                                              a: SchemaFor[A],
                                              b: SchemaFor[B],
                                              c: SchemaFor[C],
                                              d: SchemaFor[D],
                                              e: SchemaFor[E]): SchemaFor[(A, B, C, D, E)] = new SchemaFor[(A, B, C, D, E)] {
    override def schema(implicit namingStrategy: NamingStrategy): Schema =
      SchemaBuilder.record("Tuple5").namespace("scala").doc(null)
        .fields()
        .name("_1").`type`(a.schema).noDefault()
        .name("_2").`type`(b.schema).noDefault()
        .name("_3").`type`(c.schema).noDefault()
        .name("_4").`type`(d.schema).noDefault()
        .name("_5").`type`(e.schema).noDefault()
        .endRecord()
  }
}

//sealed trait Default
//
//object Default {
//  def apply(x: AnyRef): Default = if (x == null) NullDefault else MethodDefault(x)
//}
//
//case class MethodDefault(value: AnyRef) extends Default
//case object NullDefault extends Default
//case object NoDefault extends Default