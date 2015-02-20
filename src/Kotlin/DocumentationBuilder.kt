package org.jetbrains.dokka

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.dokka.DocumentationNode.*
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.builtins.*
import org.jetbrains.kotlin.name.*
import org.jetbrains.kotlin.resolve.lazy.*
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.descriptors.annotations.Annotated
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.resolve.constants.CompileTimeConstant
import com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.descriptors.impl.EnumEntrySyntheticClassDescriptor
import java.io.File
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.psi.JetParameter
import org.jetbrains.kotlin.kdoc.psi.impl.KDocSection
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.kdoc.psi.impl.KDocTag
import org.jetbrains.kotlin.idea.kdoc.resolveKDocLink
import org.jetbrains.kotlin.idea.kdoc.KDocFinder

public data class DocumentationOptions(val includeNonPublic: Boolean = false,
                                       val sourceLinks: List<SourceLinkDefinition>)

private fun isSamePackage(descriptor1: DeclarationDescriptor, descriptor2: DeclarationDescriptor): Boolean {
    val package1 = DescriptorUtils.getParentOfType(descriptor1, javaClass<PackageFragmentDescriptor>())
    val package2 = DescriptorUtils.getParentOfType(descriptor2, javaClass<PackageFragmentDescriptor>())
    return package1 != null && package2 != null && package1.fqName == package2.fqName
}

class PendingLink(val lazyNodeFrom: () -> DocumentationNode?,
                  val lazyNodeTo: () -> DocumentationNode?,
                  val kind: DocumentationReference.Kind)

class DocumentationBuilder(val session: ResolveSession, val options: DocumentationOptions, val logger: DokkaLogger) {
    val visibleToDocumentation = setOf(Visibilities.INTERNAL, Visibilities.PROTECTED, Visibilities.PUBLIC)
    val descriptorToNode = hashMapOf<DeclarationDescriptor, DocumentationNode>()
    val nodeToDescriptor = hashMapOf<DocumentationNode, DeclarationDescriptor>()
    val links = arrayListOf<PendingLink>()

    fun parseDocumentation(descriptor: DeclarationDescriptor): Content {
        val kdoc = KDocFinder.findKDoc(descriptor)
        if (kdoc == null) {
            return Content.Empty
        }
        var kdocText = kdoc.getContent()
        // workaround for code fence parsing problem in IJ markdown parser
        if (kdocText.endsWith("```") || kdocText.endsWith("~~~")) {
            kdocText += "\n"
        }
        val tree = parseMarkdown(kdocText)
        //println(tree.toTestString())
        val content = buildContent(tree)
        if (kdoc is KDocSection) {
            val tags = kdoc.getTags()
            tags.forEach {
                when (it.getName()) {
                    "sample" ->
                        content.append(functionBody(descriptor, it.getSubjectName()))
                    "see" ->
                        content.addTagToSeeAlso(it)
                    else -> {
                        val section = content.addSection(javadocSectionDisplayName(it.getName()), it.getSubjectName())
                        val sectionContent = it.getContent()
                        val markdownNode = parseMarkdown(sectionContent)
                        buildInlineContentTo(markdownNode, section)
                    }
                }
            }
        }
        return content
    }

    fun KDocSection.getTags(): Array<KDocTag> = PsiTreeUtil.getChildrenOfType(this, javaClass<KDocTag>()) ?: array()

    private fun Content.addTagToSeeAlso(seeTag: KDocTag) {
        val subjectName = seeTag.getSubjectName()
        if (subjectName != null) {
            val seeSection = findSectionByTag("See Also") ?: addSection("See Also", null)
            val link = ContentExternalLink(subjectName)
            link.append(ContentText(subjectName))
            val para = ContentParagraph()
            para.append(link)
            seeSection.append(para)
        }
    }

    fun link(node: DocumentationNode, descriptor: DeclarationDescriptor) {
        links.add(PendingLink({() -> node}, {() -> descriptorToNode[descriptor]}, DocumentationReference.Kind.Link))
    }

    fun link(fromDescriptor: DeclarationDescriptor?, toDescriptor: DeclarationDescriptor?, kind: DocumentationReference.Kind) {
        if (fromDescriptor != null && toDescriptor != null) {
            links.add(PendingLink({() -> descriptorToNode[fromDescriptor]}, {() -> descriptorToNode[toDescriptor]}, kind))
        }
    }

    fun register(descriptor: DeclarationDescriptor, node: DocumentationNode) {
        descriptorToNode.put(descriptor, node)
        nodeToDescriptor.put(node, descriptor)
    }

    fun DocumentationNode<T>(descriptor: T, kind: Kind): DocumentationNode where T : DeclarationDescriptor, T : Named {
        val doc = parseDocumentation(descriptor)
        val node = DocumentationNode(descriptor.getName().asString(), doc, kind).withModifiers(descriptor)
        return node
    }

    private fun DocumentationNode.withModifiers(descriptor: DeclarationDescriptor) : DocumentationNode{
        if (descriptor is MemberDescriptor) {
            appendVisibility(descriptor)
            if (descriptor !is ConstructorDescriptor) {
                appendModality(descriptor)
            }
        }
        return this
    }

    fun DocumentationNode.appendModality(descriptor: MemberDescriptor) {
        var modality = descriptor.getModality()
        if (modality == Modality.OPEN) {
            val containingClass = descriptor.getContainingDeclaration() as? ClassDescriptor
            if (containingClass?.getModality() == Modality.FINAL) {
                modality = Modality.FINAL
            }
        }
        val modifier = modality.name().toLowerCase()
        appendTextNode(modifier, DocumentationNode.Kind.Modifier)
    }

    fun DocumentationNode.appendVisibility(descriptor: DeclarationDescriptorWithVisibility) {
        val modifier = descriptor.getVisibility().toString()
        appendTextNode(modifier, DocumentationNode.Kind.Modifier)
    }

    fun DocumentationNode.appendSupertypes(descriptor: ClassDescriptor) {
        val superTypes = descriptor.getTypeConstructor().getSupertypes()
        for (superType in superTypes) {
            if (!ignoreSupertype(superType)) {
                appendType(superType, DocumentationNode.Kind.Supertype)
                link(superType?.getConstructor()?.getDeclarationDescriptor(), descriptor, DocumentationReference.Kind.Inheritor)
            }
        }
    }

    private fun ignoreSupertype(superType: JetType): Boolean {
        val superClass = superType.getConstructor()?.getDeclarationDescriptor() as? ClassDescriptor
        if (superClass != null) {
            val fqName = DescriptorUtils.getFqNameSafe(superClass).asString()
            return fqName == "kotlin.Annotation" || fqName == "kotlin.Enum" || fqName == "kotlin.Any"
        }
        return false
    }

    fun DocumentationNode.appendProjection(projection: TypeProjection, kind: DocumentationNode.Kind = DocumentationNode.Kind.Type) {
        val prefix = when (projection.getProjectionKind()) {
            Variance.IN_VARIANCE -> "in "
            Variance.OUT_VARIANCE -> "out "
            else -> ""
        }
        appendType(projection.getType(), kind, prefix)
    }

    fun DocumentationNode.appendType(jetType: JetType?, kind: DocumentationNode.Kind = DocumentationNode.Kind.Type, prefix: String = "") {
        if (jetType == null)
            return
        val classifierDescriptor = jetType.getConstructor().getDeclarationDescriptor()
        val name = when (classifierDescriptor) {
            is Named -> prefix + classifierDescriptor.getName().asString() + if (jetType.isMarkedNullable()) "?" else ""
            else -> "<anonymous>"
        }
        val node = DocumentationNode(name, Content.Empty, kind)
        if (classifierDescriptor != null)
            link(node, classifierDescriptor)

        append(node, DocumentationReference.Kind.Detail)
        for (typeArgument in jetType.getArguments())
            node.appendProjection(typeArgument)
    }

    fun DocumentationNode.appendAnnotations(annotated: Annotated) {
        annotated.getAnnotations().forEach {
            val annotationNode = it.build()
            if (annotationNode != null) {
                append(annotationNode,
                        if (annotationNode.name == "deprecated") DocumentationReference.Kind.Deprecation else DocumentationReference.Kind.Annotation)
            }
        }
    }

    fun DocumentationNode.appendSourceLink(sourceElement: SourceElement) {
        appendSourceLink(sourceElement.getPsi(), options.sourceLinks)
    }

    fun DocumentationNode.appendChild(descriptor: DeclarationDescriptor, kind: DocumentationReference.Kind): DocumentationNode? {
        // do not include generated code
        if (descriptor is CallableMemberDescriptor && descriptor.getKind() != CallableMemberDescriptor.Kind.DECLARATION)
            return null

        if (options.includeNonPublic
                || descriptor !is MemberDescriptor
                || descriptor.getVisibility() in visibleToDocumentation) {
            val node = descriptor.build()
            append(node, kind)
            return node
        }
        return null
    }

    fun DocumentationNode.appendChildren(descriptors: Iterable<DeclarationDescriptor>, kind: DocumentationReference.Kind) {
        descriptors.forEach { descriptor -> appendChild(descriptor, kind) }
    }

    fun DocumentationNode.appendInPageChildren(descriptors: Iterable<DeclarationDescriptor>, kind: DocumentationReference.Kind) {
        descriptors.forEach { descriptor ->
            val node = appendChild(descriptor, kind)
            if (node != null) {
                node.addReferenceTo(this, DocumentationReference.Kind.TopLevelPage)
            }
        }
    }

    fun DocumentationNode.getParentForPackageMember(descriptor: DeclarationDescriptor,
                                                    externalClassNodes: MutableMap<FqName, DocumentationNode>): DocumentationNode {
        if (descriptor is CallableMemberDescriptor) {
            val extensionClassDescriptor = descriptor.getExtensionClassDescriptor()
            if (extensionClassDescriptor != null && !isSamePackage(descriptor, extensionClassDescriptor)) {
                val fqName = DescriptorUtils.getFqNameFromTopLevelClass(extensionClassDescriptor)
                return externalClassNodes.getOrPut(fqName, {
                    val newNode = DocumentationNode(fqName.asString(), Content.Empty, Kind.ExternalClass)
                    append(newNode, DocumentationReference.Kind.Member)
                    newNode
                })
            }
        }
        return this
    }

    fun DocumentationNode.appendFragments(fragments: Collection<PackageFragmentDescriptor>) {
        val descriptors = hashMapOf<String, List<DeclarationDescriptor>>()
        for ((name, parts) in fragments.groupBy { it.fqName }) {
            descriptors.put(name.asString(), parts.flatMap { it.getMemberScope().getAllDescriptors() })
        }
        for ((packageName, declarations) in descriptors) {
            logger.info("  package $packageName: ${declarations.count()} declarations")
            val packageNode = findOrCreatePackageNode(packageName)
            val externalClassNodes = hashMapOf<FqName, DocumentationNode>()
            declarations.forEach { descriptor ->
                val parent = packageNode.getParentForPackageMember(descriptor, externalClassNodes)
                parent.appendChild(descriptor, DocumentationReference.Kind.Member)
            }
        }
    }

    fun DeclarationDescriptor.build(): DocumentationNode = when (this) {
        is ClassDescriptor -> build()
        is ConstructorDescriptor -> build()
        is ScriptDescriptor -> build()
        is PropertyDescriptor -> build()
        is PropertyAccessorDescriptor -> build()
        is FunctionDescriptor -> build()
        is TypeParameterDescriptor -> build()
        is ValueParameterDescriptor -> build()
        is ReceiverParameterDescriptor -> build()
        else -> throw IllegalStateException("Descriptor $this is not known")
    }

    fun ScriptDescriptor.build(): DocumentationNode = getClassDescriptor().build()
    fun ClassDescriptor.build(): DocumentationNode {
        val kind = when (getKind()) {
            ClassKind.OBJECT -> Kind.Object
            ClassKind.CLASS_OBJECT -> Kind.Object
            ClassKind.TRAIT -> Kind.Interface
            ClassKind.ENUM_CLASS -> Kind.Enum
            ClassKind.ANNOTATION_CLASS -> Kind.AnnotationClass
            ClassKind.ENUM_ENTRY -> Kind.EnumItem
            else -> Kind.Class
        }
        val node = DocumentationNode(this, kind)
        if (isInner()) {
            node.appendTextNode("inner", Kind.Modifier)
        }
        node.appendSupertypes(this)
        if (getKind() != ClassKind.OBJECT && getKind() != ClassKind.ENUM_ENTRY) {
            node.appendInPageChildren(getTypeConstructor().getParameters(), DocumentationReference.Kind.Detail)
            val constructorsToDocument = if (getKind() == ClassKind.ENUM_CLASS)
                getConstructors().filter { it.getValueParameters().size() > 0 }
            else
                getConstructors()
            node.appendChildren(constructorsToDocument, DocumentationReference.Kind.Member)
        }
        val members = getDefaultType().getMemberScope().getAllDescriptors().filter { it != getDefaultObjectDescriptor() }
        node.appendChildren(members, DocumentationReference.Kind.Member)
        val classObjectDescriptor = getDefaultObjectDescriptor()
        if (classObjectDescriptor != null) {
            node.appendChildren(classObjectDescriptor.getDefaultType().getMemberScope().getAllDescriptors(),
                    DocumentationReference.Kind.Member)
        }
        node.appendAnnotations(this)
        node.appendSourceLink(getSource())
        register(this, node)
        return node
    }

    fun ConstructorDescriptor.build(): DocumentationNode {
        val node = DocumentationNode(this, Kind.Constructor)
        node.appendInPageChildren(getValueParameters(), DocumentationReference.Kind.Detail)
        register(this, node)
        return node
    }

    private fun DeclarationDescriptor.inClassObject() =
            getContainingDeclaration().let { it is ClassDescriptor && it.getKind() == ClassKind.CLASS_OBJECT }

    fun CallableMemberDescriptor.getExtensionClassDescriptor(): ClassifierDescriptor? {
        val extensionReceiver = getExtensionReceiverParameter()
        if (extensionReceiver != null) {
            val type = extensionReceiver.getType()
            return type.getConstructor().getDeclarationDescriptor() as? ClassDescriptor
        }
        return null
    }

    fun FunctionDescriptor.build(): DocumentationNode {
        val node = DocumentationNode(this, if (inClassObject()) Kind.DefaultObjectFunction else Kind.Function)

        node.appendInPageChildren(getTypeParameters(), DocumentationReference.Kind.Detail)
        getExtensionReceiverParameter()?.let { node.appendChild(it, DocumentationReference.Kind.Detail) }
        node.appendInPageChildren(getValueParameters(), DocumentationReference.Kind.Detail)
        node.appendType(getReturnType())
        node.appendAnnotations(this)
        node.appendSourceLink(getSource())

        getOverriddenDescriptors().forEach {
            addOverrideLink(it, this)
        }

        register(this, node)
        return node
    }

    fun addOverrideLink(baseClassFunction: FunctionDescriptor, overridingFunction: FunctionDescriptor) {
        val source = baseClassFunction.getOriginal().getSource().getPsi()
        if (source != null) {
            link(overridingFunction, baseClassFunction, DocumentationReference.Kind.Override)
        } else {
            baseClassFunction.getOverriddenDescriptors().forEach {
                addOverrideLink(it, overridingFunction)
            }
        }
    }

    fun PropertyAccessorDescriptor.build(): DocumentationNode {
        val doc = parseDocumentation(this)
        val specialName = getName().asString().drop(1).takeWhile { it != '-' }
        val node = DocumentationNode(specialName, doc, Kind.PropertyAccessor).withModifiers(this)

        node.appendInPageChildren(getValueParameters(), DocumentationReference.Kind.Detail)
        node.appendType(getReturnType())
        register(this, node)
        return node
    }

    fun PropertyDescriptor.build(): DocumentationNode {
        val node = DocumentationNode(this, if (inClassObject()) Kind.DefaultObjectProperty else Kind.Property)
        node.appendInPageChildren(getTypeParameters(), DocumentationReference.Kind.Detail)
        getExtensionReceiverParameter()?.let { node.appendChild(it, DocumentationReference.Kind.Detail) }
        node.appendType(getReturnType())
        node.appendAnnotations(this)
        node.appendSourceLink(getSource())
        if (isVar()) {
            node.appendTextNode("var", DocumentationNode.Kind.Modifier)
        }
        getGetter()?.let {
            if (!it.isDefault())
                node.appendChild(it, DocumentationReference.Kind.Member)
        }
        getSetter()?.let {
            if (!it.isDefault())
                node.appendChild(it, DocumentationReference.Kind.Member)
        }

        register(this, node)
        return node
    }

    fun ValueParameterDescriptor.build(): DocumentationNode {
        val node = DocumentationNode(this, Kind.Parameter)
        val varargType = getVarargElementType()
        if (varargType != null) {
            node.appendTextNode("vararg", Kind.Annotation, DocumentationReference.Kind.Annotation)
            node.appendType(varargType)
        } else {
            node.appendType(getType())
        }
        if (hasDefaultValue()) {
            val psi = getSource().getPsi() as? JetParameter
            if (psi != null) {
                val defaultValueText = psi.getDefaultValue()?.getText()
                if (defaultValueText != null) {
                    node.appendTextNode(defaultValueText, Kind.Value)
                }
            }
        }
        node.appendAnnotations(this)
        register(this, node)
        return node
    }

    fun TypeParameterDescriptor.build(): DocumentationNode {
        val doc = parseDocumentation(this)
        val name = getName().asString()
        val prefix = when (getVariance()) {
            Variance.IN_VARIANCE -> "in "
            Variance.OUT_VARIANCE -> "out "
            else -> ""
        }

        val node = DocumentationNode(prefix + name, doc, DocumentationNode.Kind.TypeParameter)

        val builtIns = KotlinBuiltIns.getInstance()
        for (constraint in getUpperBounds()) {
            if (constraint == builtIns.getDefaultBound())
                continue
            node.appendTextNode(constraint.toString(), DocumentationNode.Kind.UpperBound)
        }

        for (constraint in getLowerBounds()) {
            if (KotlinBuiltIns.isNothing(constraint))
                continue
            node.appendTextNode(constraint.toString(), DocumentationNode.Kind.LowerBound)
        }
        return node
    }

    fun ReceiverParameterDescriptor.build(): DocumentationNode {
        link(getType().getConstructor().getDeclarationDescriptor(),
                getContainingDeclaration(),
                DocumentationReference.Kind.Extension)

        val node = DocumentationNode(getName().asString(), Content.Empty, Kind.Receiver)
        node.appendType(getType())
        return node
    }

    fun AnnotationDescriptor.build(): DocumentationNode? {
        val annotationClass = getType().getConstructor().getDeclarationDescriptor()
        if (ErrorUtils.isError(annotationClass)) {
            return null
        }
        val node = DocumentationNode(annotationClass.getName().asString(), Content.Empty, DocumentationNode.Kind.Annotation)
        val arguments = getAllValueArguments().toList().sortBy { it.first.getIndex() }
        arguments.forEach {
            val valueNode = it.second.build()
            if (valueNode != null) {
                val paramNode = DocumentationNode(it.first.getName().asString(), Content.Empty, DocumentationNode.Kind.Parameter)
                paramNode.append(valueNode, DocumentationReference.Kind.Detail)
                node.append(paramNode, DocumentationReference.Kind.Detail)
            }
        }
        return node
    }

    fun CompileTimeConstant<out Any?>.build(): DocumentationNode? {
        val value = getValue()
        val valueString = when(value) {
            is String ->
                "\"" + StringUtil.escapeStringCharacters(value) + "\""
            is EnumEntrySyntheticClassDescriptor ->
                value.getContainingDeclaration().getName().asString() + "." + value.getName()
            else -> value?.toString()
        }
        return if (valueString != null) DocumentationNode(valueString, Content.Empty, DocumentationNode.Kind.Value) else null
    }

    /**
     * Generates cross-references for documentation such as extensions for a type, inheritors, etc
     *
     * $receiver: [DocumentationContext] for node/descriptor resolutions
     * $node: [DocumentationNode] to visit
     */
    public fun resolveReferences(node: DocumentationModule) {
        for (link in links) {
            val fromNode = link.lazyNodeFrom()
            val toNode = link.lazyNodeTo()
            if (fromNode != null && toNode != null) {
                fromNode.addReferenceTo(toNode, link.kind)
            }
        }
        resolveContentReferences(node)
    }

    private fun resolveContentReferences(node: DocumentationNode) {
        resolveContentLinks(node, node.content)
        for (section in node.content.sections) {
            resolveContentLinks(node, section)
        }

        for (child in node.members) {
            resolveContentReferences(child)
        }
        for (child in node.details) {
            resolveContentReferences(child)
        }
    }

    fun getDescriptorForNode(node: DocumentationNode): DeclarationDescriptor {
        val descriptor = nodeToDescriptor[node] ?: throw IllegalArgumentException("Node is not known to this context")
        return descriptor
    }

    fun resolveContentLinks(node: DocumentationNode, content: ContentBlock) {
        val resolvedContentChildren = content.children.map { resolveContentLink(node, it) }
        content.children.clear()
        content.children.addAll(resolvedContentChildren)
    }

    private fun resolveContentLink(node: DocumentationNode, content: ContentNode): ContentNode {
        if (content is ContentExternalLink) {
            val referenceText = content.href
            val symbols = resolveKDocLink(session, getDescriptorForNode(node), null, referenceText.split('.').toList())
            // don't include unresolved links in generated doc
            // assume that if an href doesn't contain '/', it's not an attempt to reference an external file
            if (symbols.isNotEmpty() || "/" !in referenceText) {
                val targetNode = if (symbols.isEmpty()) null else descriptorToNode[symbols.first()]
                val contentLink = if (targetNode != null) ContentNodeLink(targetNode) else ContentExternalLink("#")
                contentLink.children.addAll(content.children.map { resolveContentLink(node, it) })
                return contentLink
            }
        }
        if (content is ContentBlock) {
            resolveContentLinks(node, content)
        }
        return content
    }
}
