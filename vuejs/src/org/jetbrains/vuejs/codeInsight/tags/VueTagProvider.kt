// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.vuejs.codeInsight.tags

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.javascript.DialectDetector
import com.intellij.lang.javascript.library.JSLibraryUtil
import com.intellij.lang.javascript.psi.stubs.JSImplicitElement
import com.intellij.lang.javascript.psi.stubs.impl.JSImplicitElementImpl
import com.intellij.lang.javascript.settings.JSApplicationSettings
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.html.dtd.HtmlNSDescriptorImpl
import com.intellij.psi.impl.source.xml.XmlDescriptorUtil
import com.intellij.psi.impl.source.xml.XmlElementDescriptorProvider
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.xml.*
import com.intellij.xml.XmlElementDescriptor.CONTENT_TYPE_ANY
import icons.VuejsIcons
import one.util.streamex.StreamEx
import org.jetbrains.vuejs.codeInsight.BOOLEAN_TYPE
import org.jetbrains.vuejs.codeInsight.attributes.VueAttributeDescriptor
import org.jetbrains.vuejs.codeInsight.attributes.VueAttributeDescriptor.AttributePriority.HIGH
import org.jetbrains.vuejs.codeInsight.attributes.VueAttributeDescriptor.AttributePriority.LOW
import org.jetbrains.vuejs.codeInsight.attributes.VueAttributeNameParser
import org.jetbrains.vuejs.codeInsight.detectVueScriptLanguage
import org.jetbrains.vuejs.codeInsight.fromAsset
import org.jetbrains.vuejs.codeInsight.toAsset
import org.jetbrains.vuejs.index.isVueContext
import org.jetbrains.vuejs.lang.html.VueFileType
import org.jetbrains.vuejs.lang.html.VueLanguage
import org.jetbrains.vuejs.model.*

private const val LOCAL_PRIORITY = 100.0
private const val APP_PRIORITY = 90.0
private const val PLUGIN_PRIORITY = 90.0
private const val GLOBAL_PRIORITY = 80.0
private const val UNREGISTERED_PRIORITY = 50.0

class VueTagProvider : XmlElementDescriptorProvider, XmlTagNameProvider {
  override fun getDescriptor(tag: XmlTag?): XmlElementDescriptor? {
    if (tag != null && tag.containingFile.language == VueLanguage.INSTANCE && isVueContext(tag)) {
      val tagName = fromAsset(tag.name)

      val components = mutableListOf<VueComponent>()
      VueModelManager.findEnclosingContainer(tag)?.acceptEntities(object : VueModelProximityVisitor() {
        override fun visitComponent(name: String, component: VueComponent, proximity: Proximity): Boolean {
          return acceptSameProximity(proximity, fromAsset(name) == tagName) {
            components.add(component)
          }
        }
      }, VueModelVisitor.Proximity.GLOBAL)

      if (components.isNotEmpty()) return VueElementDescriptor(tag, components)

      if (VUE_FRAMEWORK_COMPONENTS.contains(tagName)) {
        return VueElementDescriptor(tag)
      }
    }
    return null
  }

  override fun addTagNameVariants(elements: MutableList<LookupElement>?, tag: XmlTag, namespacePrefix: String?) {
    elements ?: return
    if (!StringUtil.isEmpty(namespacePrefix) || !isVueContext(tag)) return

    val scriptLanguage = detectVueScriptLanguage(tag.containingFile)

    val nameMapper: (String) -> List<String> = if (VueFileType.INSTANCE == tag.containingFile.fileType)
      { name -> listOf(toAsset(name).capitalize(), fromAsset(name)) }
    else
      { name -> listOf(fromAsset(name)) }

    val providedNames = mutableSetOf<String>()
    VueModelManager.findEnclosingContainer(tag)?.acceptEntities(object : VueModelVisitor() {
      override fun visitComponent(name: String, component: VueComponent, proximity: Proximity): Boolean {
        val moduleName: String? = if (component.parents.size == 1) {
          (component.parents.first() as? VuePlugin)?.moduleName
        }
        else null
        nameMapper(name).forEach {
          if (providedNames.add(it)) {
            elements.add(createVueLookup(component.source, it,
                                         proximity != Proximity.OUT_OF_SCOPE,
                                         scriptLanguage,
                                         priorityOf(proximity),
                                         moduleName))
          }
        }
        return true
      }
    }, VueModelVisitor.Proximity.OUT_OF_SCOPE)

    elements.addAll(VUE_FRAMEWORK_COMPONENTS.map {
      LookupElementBuilder.create(it).withIcon(VuejsIcons.Vue).withTypeText("vue", true)
    })
  }

  private fun priorityOf(proximity: VueModelVisitor.Proximity): Double {
    return when (proximity) {
      VueModelVisitor.Proximity.OUT_OF_SCOPE -> UNREGISTERED_PRIORITY
      VueModelVisitor.Proximity.GLOBAL -> GLOBAL_PRIORITY
      VueModelVisitor.Proximity.APP -> APP_PRIORITY
      VueModelVisitor.Proximity.PLUGIN -> PLUGIN_PRIORITY
      VueModelVisitor.Proximity.LOCAL -> LOCAL_PRIORITY
    }
  }

  private fun createVueLookup(element: PsiElement?,
                              name: String,
                              shouldNotBeImported: Boolean,
                              scriptLanguage: String?,
                              priority: Double,
                              moduleName: String? = null): LookupElement {
    var builder = (if (element != null) LookupElementBuilder.create(element, name) else LookupElementBuilder.create(name))
      .withIcon(VuejsIcons.Vue)
    if (priority == LOCAL_PRIORITY) {
      builder = builder.bold()
    }
    if (moduleName != null) {
      builder = builder.withTypeText(moduleName, true)
    }
    if (!shouldNotBeImported && element != null) {
      val settings = JSApplicationSettings.getInstance()
      if ((scriptLanguage != null && "ts" == scriptLanguage)
          || (DialectDetector.isTypeScript(element)
              && !JSLibraryUtil.isProbableLibraryFile(element.containingFile.viewProvider.virtualFile))) {
        if (settings.hasTSImportCompletionEffective(element.project)) {
          builder = builder.withInsertHandler(VueInsertHandler.INSTANCE)
        }
      }
      else {
        if (settings.isUseJavaScriptAutoImport) {
          builder = builder.withInsertHandler(VueInsertHandler.INSTANCE)
        }
      }
    }
    return PrioritizedLookupElement.withPriority(builder, priority)
  }

  companion object {
    private val VUE_FRAMEWORK_COMPONENTS = setOf(
      "component",
      "slot"
    )
  }
}

class VueElementDescriptor(private val tag: XmlTag, private val sources: Collection<VueComponent> = emptyList()) : XmlElementDescriptor {
  companion object {
    private fun isBooleanProp(prop: VueInputProperty): Boolean {
      return prop.jsType?.isDirectlyAssignableType(BOOLEAN_TYPE, null) ?: false
    }
  }

  override fun getDeclaration(): JSImplicitElement {
    return StreamEx.of(sources)
      .map {
        it.source?.let { source ->
          VueModelManager.getComponentImplicitElement(source)
          ?: JSImplicitElementImpl(JSImplicitElementImpl.Builder(tag.name, source).forbidAstAccess())
        }
      }
      .select(JSImplicitElement::class.java)
      .findFirst()
      .orElseGet { JSImplicitElementImpl(JSImplicitElementImpl.Builder(tag.name, tag).forbidAstAccess()) }
  }

  override fun getName(context: PsiElement?): String = (context as? XmlTag)?.name ?: name
  override fun getName(): String = fromAsset(declaration.name)
  override fun init(element: PsiElement?) {}
  override fun getQualifiedName(): String = name
  override fun getDefaultName(): String = name

  override fun getElementsDescriptors(context: XmlTag): Array<XmlElementDescriptor> {
    return XmlDescriptorUtil.getElementsDescriptors(context)
  }

  override fun getElementDescriptor(childTag: XmlTag, contextTag: XmlTag): XmlElementDescriptor? {
    return XmlDescriptorUtil.getElementDescriptor(childTag, contextTag)
  }

  override fun getAttributesDescriptors(context: XmlTag?): Array<out XmlAttributeDescriptor> {
    val result = mutableListOf<XmlAttributeDescriptor>()
    val commonHtmlAttributes = HtmlNSDescriptorImpl.getCommonAttributeDescriptors(context)
    result.addAll(commonHtmlAttributes)
    result.addAll(getProps())
    return result.toTypedArray()
  }

  fun getPsiSources(): List<PsiElement> {
    return sources.mapNotNull { it.source }
      .ifEmpty { listOf(JSImplicitElementImpl(JSImplicitElementImpl.Builder(tag.name, tag).forbidAstAccess())) }
  }

  fun getProps(): List<XmlAttributeDescriptor> {
    return StreamEx.of(sources)
      .flatCollection {
        val result = mutableListOf<XmlAttributeDescriptor>()
        it.acceptPropertiesAndMethods(object : VueModelVisitor() {
          override fun visitInputProperty(prop: VueInputProperty, proximity: Proximity): Boolean {
            result.add(VueAttributeDescriptor(fromAsset(prop.name), prop.source, acceptsNoValue = isBooleanProp(prop), priority = HIGH))
            return true
          }
        })
        result
      }
      .toList()
  }

  fun getEmitCalls(): List<VueEmitCall> {
    return StreamEx.of(sources)
      .select(VueContainer::class.java)
      .flatCollection { it.emits }
      .toList()
  }

  override fun getAttributeDescriptor(attributeName: String?, context: XmlTag?): XmlAttributeDescriptor? {
    val info = VueAttributeNameParser.parse(attributeName ?: return null, context)

    if (info is VueAttributeNameParser.VueDirectiveInfo && info.arguments != null) {
      if (info.directiveKind === VueAttributeNameParser.VueDirectiveKind.BIND) {
        return resolveToProp(info.arguments, attributeName) ?: VueAttributeDescriptor(attributeName, acceptsNoValue = false, priority = LOW)
      }
      // TODO resolve component events
    }
    if (info.kind === VueAttributeNameParser.VueAttributeKind.PLAIN) {
      resolveToProp(info.name, attributeName)?.let { return it }
    }

    return HtmlNSDescriptorImpl.getCommonAttributeDescriptor(attributeName, context)
           // relax attributes check: https://vuejs.org/v2/guide/components.html#Non-Prop-Attributes
           // vue allows any non-declared as props attributes to be passed to a component
           ?: VueAttributeDescriptor(attributeName, acceptsNoValue = !info.requiresValue
                                                                     || info.kind === VueAttributeNameParser.VueAttributeKind.PLAIN
                                                                     || (info is VueAttributeNameParser.VueDirectiveInfo
                                                                         && info.directiveKind === VueAttributeNameParser.VueDirectiveKind.CUSTOM),
                                     priority = LOW)
  }

  private fun resolveToProp(propName: String, attributeName: String): XmlAttributeDescriptor? {
    val propFromAsset = fromAsset(propName)
    return StreamEx.of(sources)
      .map {
        var result: XmlAttributeDescriptor? = null
        it.acceptPropertiesAndMethods(object : VueModelVisitor() {
          override fun visitInputProperty(prop: VueInputProperty, proximity: Proximity): Boolean {
            if (propFromAsset == fromAsset(prop.name)) {
              result = VueAttributeDescriptor(attributeName, prop.source, acceptsNoValue = isBooleanProp(prop), priority = HIGH)
              return false
            }
            return true
          }
        })
        result
      }
      .nonNull()
      .findFirst()
      .orElse(null)
  }

  override fun getAttributeDescriptor(attribute: XmlAttribute?): XmlAttributeDescriptor? = getAttributeDescriptor(attribute?.name,
                                                                                                                  attribute?.parent)

  override fun getNSDescriptor(): XmlNSDescriptor? = null
  override fun getTopGroup(): XmlElementsGroup? = null
  override fun getContentType(): Int = CONTENT_TYPE_ANY
  override fun getDefaultValue(): String? = null
}
