package com.github.jrubygradle.jar.internal

/*
 * This source code is derived from Apache 2.0 licensed software copyright John
 * Engelman (https://github.com/johnrengelman) and was originally ported from this
 * repository: https://github.com/johnrengelman/shadow
*/

import com.github.jengelman.gradle.plugins.shadow.impl.RelocatorRemapper
import com.github.jengelman.gradle.plugins.shadow.internal.ZipCompressor
import com.github.jengelman.gradle.plugins.shadow.relocation.Relocator
import com.github.jengelman.gradle.plugins.shadow.transformers.Transformer
import groovy.util.logging.Slf4j
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.IOUtils
import org.apache.tools.zip.UnixStat
import org.apache.tools.zip.Zip64RequiredException
import org.apache.tools.zip.ZipEntry
import org.apache.tools.zip.ZipFile
import org.apache.tools.zip.ZipOutputStream
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.UncheckedIOException
import org.gradle.api.file.FileCopyDetails
import org.gradle.api.file.FileTreeElement
import org.gradle.api.file.RelativePath
import org.gradle.api.internal.DocumentationRegistry
import org.gradle.api.internal.file.CopyActionProcessingStreamAction
import org.gradle.api.internal.file.copy.CopyAction
import org.gradle.api.internal.file.copy.CopyActionProcessingStream
import org.gradle.api.internal.file.copy.FileCopyDetailsInternal
import org.gradle.api.internal.tasks.SimpleWorkResult
import org.gradle.api.tasks.WorkResult
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.util.PatternSet
import org.gradle.internal.UncheckedException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.RemappingClassAdapter

import java.util.zip.ZipException

/**
 * JRubyJarCopyAction is an implementation of the {@link CopyAction} interface for mutating the JRubyJar archive.
 *
 * This class, in its current form is really just a big copy and paste of the
 * shadow plugin's <a
 * href="https://github.com/johnrengelman/shadow/blob/51f2b5916edd7690dca5e89e8b3a8f330d435423/src/main/groovy/com/github/jengelman/gradle/plugins/shadow/tasks/ShadowCopyAction.groovy">ShadowCopyAction</a>.
 * with one notable exception, it disables the behavior of unzipping nested
 * archives when creating the resulting archive.
 *
 * This class is only intended to be used with the {@link
 * JRubyDirInfoTransformer} until such a time when this can be refactored to
 * support the same behavior in a less hackish way.
 */
@Slf4j
@SuppressWarnings(['ParameterCount', 'CatchException', 'DuplicateStringLiteral',
'CatchThrowable', 'VariableName'])
class JRubyJarCopyAction implements CopyAction {

    private final File zipFile
    private final ZipCompressor compressor
    private final DocumentationRegistry documentationRegistry
    private final List<Transformer> transformers
    private final List<Relocator> relocators
    private final PatternSet patternSet

    JRubyJarCopyAction(File zipFile, ZipCompressor compressor, DocumentationRegistry documentationRegistry,
                            List<Transformer> transformers, List<Relocator> relocators, PatternSet patternSet) {

        this.zipFile = zipFile
        this.compressor = compressor
        this.documentationRegistry = documentationRegistry
        this.transformers = transformers
        this.relocators = relocators
        this.patternSet = patternSet
    }

    @Override
    WorkResult execute(CopyActionProcessingStream stream) {
        final ZipOutputStream zipOutStr

        try {
            zipOutStr = compressor.createArchiveOutputStream(zipFile)
        } catch (Exception e) {
            throw new GradleException("Could not create ZIP '${zipFile.toString()}'", e)
        }

        try {
            withResource(zipOutStr, new Action<ZipOutputStream>() {
                void execute(ZipOutputStream outputStream) {
                    try {
                        stream.process(new StreamAction(outputStream, transformers, relocators, patternSet))
                        processTransformers(outputStream)
                    } catch (Exception e) {
                        log.error('ex', e)
                        //TODO this should not be rethrown
                        throw e
                    }
                }
            })
        } catch (UncheckedIOException e) {
            if (e.cause instanceof Zip64RequiredException) {
                throw new Zip64RequiredException(
                        String.format('%s\n\nTo build this archive, please enable the zip64 extension.\nSee: %s',
                                e.cause.message, documentationRegistry.getDslRefForProperty(Zip, 'zip64'))
                )
            }
        }
        return new SimpleWorkResult(true)
    }

    private void processTransformers(ZipOutputStream s) {
        transformers.each { Transformer transformer ->
            if (transformer.hasTransformedResource()) {
                transformer.modifyOutputStream(s)
            }
        }
    }

    @SuppressWarnings('EmptyCatchBlock')
    private static <T extends Closeable> void withResource(T resource, Action<? super T> action) {
        try {
            action.execute(resource)
        } catch (Throwable t) {
            try {
                resource.close()
            } catch (IOException e) {
                // Ignored
            }
            throw UncheckedException.throwAsUncheckedException(t)
        }

        try {
            resource.close()
        } catch (IOException e) {
            throw new UncheckedIOException(e)
        }
    }

    class StreamAction implements CopyActionProcessingStreamAction {

        private final ZipOutputStream zipOutStr
        private final List<Transformer> transformers
        private final List<Relocator> relocators
        private final RelocatorRemapper remapper
        private final PatternSet patternSet

        private final Set<String> visitedFiles = [] as Set

        StreamAction(ZipOutputStream zipOutStr, List<Transformer> transformers, List<Relocator> relocators,
                            PatternSet patternSet) {
            this.zipOutStr = zipOutStr
            this.transformers = transformers
            this.relocators = relocators
            this.remapper = new RelocatorRemapper(relocators)
            this.patternSet = patternSet
        }

        void processFile(FileCopyDetailsInternal details) {
            if (details.directory) {
                visitDir(details)
            } else {
                visitFile(details)
            }
        }

        private boolean recordVisit(RelativePath path) {
            return visitedFiles.add(path.pathString)
        }

        private void visitFile(FileCopyDetails fileDetails) {
            try {
                boolean isClass = (FilenameUtils.getExtension(fileDetails.path) == 'class')
                if (!remapper.hasRelocators() || !isClass) {
                    if (isTransformable(fileDetails)) {
                        transform(fileDetails)
                    }
                    else {
                        String mappedPath = remapper.map(fileDetails.relativePath.pathString)
                        ZipEntry archiveEntry = new ZipEntry(mappedPath)
                        archiveEntry.setTime(fileDetails.lastModified)
                        archiveEntry.unixMode = (UnixStat.FILE_FLAG | fileDetails.mode)
                        zipOutStr.putNextEntry(archiveEntry)
                        fileDetails.copyTo(zipOutStr)
                        zipOutStr.closeEntry()
                    }
                } else if (isClass) {
                    remapClass(fileDetails)
                }
                recordVisit(fileDetails.relativePath)
            } catch (Exception e) {
                throw new GradleException(String.format('Could not add %s to ZIP \'%s\'.', fileDetails, zipFile), e)
            }
        }

        private void visitArchiveDirectory(RelativeArchivePath archiveDir) {
            if (recordVisit(archiveDir)) {
                zipOutStr.putNextEntry(archiveDir.entry)
                zipOutStr.closeEntry()
            }
        }

        private void addParentDirectories(RelativeArchivePath file) {
            if (file) {
                addParentDirectories(file.parent)
                if (!file.file) {
                    visitArchiveDirectory(file)
                }
            }
        }

        private void remapClass(RelativeArchivePath file, ZipFile archive) {
            if (file.classFile) {
                addParentDirectories(new RelativeArchivePath(new ZipEntry(remapper.mapPath(file) + '.class'), null))
                remapClass(archive.getInputStream(file.entry), file.pathString)
            }
        }

        private void remapClass(FileCopyDetails fileCopyDetails) {
            if (FilenameUtils.getExtension(fileCopyDetails.name) == 'class') {
                remapClass(fileCopyDetails.file.newInputStream(), fileCopyDetails.path)
            }
        }

        private void remapClass(InputStream classInputStream, String path) {
            InputStream is = classInputStream
            ClassReader cr = new ClassReader(is)

            // We don't pass the ClassReader here. This forces the ClassWriter to rebuild the constant pool.
            // Copying the original constant pool should be avoided because it would keep references
            // to the original class names. This is not a problem at runtime (because these entries in the
            // constant pool are never used), but confuses some tools such as Felix' maven-bundle-plugin
            // that use the constant pool to determine the dependencies of a class.
            ClassWriter cw = new ClassWriter(0)

            ClassVisitor cv = new RemappingClassAdapter(cw, remapper)

            try {
                cr.accept(cv, ClassReader.EXPAND_FRAMES)
            } catch (Throwable ise) {
                throw new GradleException('Error in ASM processing class ' + path, ise)
            }

            byte[] renamedClass = cw.toByteArray()

            // Need to take the .class off for remapping evaluation
            String mappedName = remapper.mapPath(path)

            try {
                // Now we put it back on so the class file is written out with the right extension.
                zipOutStr.putNextEntry(new ZipEntry(mappedName + '.class'))
                IOUtils.copyLarge(new ByteArrayInputStream(renamedClass), zipOutStr)
                zipOutStr.closeEntry()
            } catch (ZipException e) {
                log.warn('We have a duplicate ' + mappedName + ' in source project')
            }
        }

        private void visitDir(FileCopyDetails dirDetails) {
            try {
                // Trailing slash in name indicates that entry is a directory
                String path = dirDetails.relativePath.pathString + '/'
                ZipEntry archiveEntry = new ZipEntry(path)
                archiveEntry.setTime(dirDetails.lastModified)
                archiveEntry.unixMode = (UnixStat.DIR_FLAG | dirDetails.mode)
                zipOutStr.putNextEntry(archiveEntry)
                zipOutStr.closeEntry()
                recordVisit(dirDetails.relativePath)
            } catch (Exception e) {
                throw new GradleException(String.format('Could not add %s to ZIP \'%s\'.', dirDetails, zipFile), e)
            }
        }

        private void transform(ArchiveFileTreeElement element, ZipFile archive) {
            transform(element, archive.getInputStream(element.relativePath.entry))
        }

        private void transform(FileCopyDetails details) {
            transform(details, details.file.newInputStream())
        }

        private void transform(FileTreeElement element, InputStream is) {
            String mappedPath = remapper.map(element.relativePath.pathString)
            transformers.find { it.canTransformResource(element) }.transform(mappedPath, is, relocators)
        }

        private boolean isTransformable(FileTreeElement element) {
            return transformers.any { it.canTransformResource(element) }
        }

    }

    class RelativeArchivePath extends RelativePath {

        ZipEntry entry
        FileCopyDetails details

        RelativeArchivePath(ZipEntry entry, FileCopyDetails fileDetails) {
            super(!entry.directory, entry.name.split('/'))
            this.entry = entry
            this.details = fileDetails
        }

        boolean isClassFile() {
            return lastName.endsWith('.class')
        }

        RelativeArchivePath getParent() {
            if (!segments || segments.length == 1) {
                return null
            }

            //Parent is always a directory so add / to the end of the path
            String path = segments[0..-2].join('/') + '/'
            return new RelativeArchivePath(new ZipEntry(path), null)
        }
    }

    @SuppressWarnings('GetterMethodCouldBeProperty')
    class ArchiveFileTreeElement implements FileTreeElement {

        private final RelativeArchivePath archivePath

        ArchiveFileTreeElement(RelativeArchivePath archivePath) {
            this.archivePath = archivePath
        }

        boolean isClassFile() {
            return archivePath.classFile
        }

        @Override
        File getFile() {
            return null
        }

        @Override
        boolean isDirectory() {
            return archivePath.entry.directory
        }

        @Override
        long getLastModified() {
            return archivePath.entry.lastModifiedDate.time
        }

        @Override
        long getSize() {
            return archivePath.entry.size
        }

        @Override
        InputStream open() {
            return null
        }

        @Override
        void copyTo(OutputStream outputStream) {

        }

        @Override
        boolean copyTo(File file) {
            return false
        }

        @Override
        String getName() {
            return archivePath.pathString
        }

        @Override
        String getPath() {
            return archivePath.lastName
        }

        @Override
        RelativeArchivePath getRelativePath() {
            return archivePath
        }

        @Override
        int getMode() {
            return archivePath.entry.unixMode
        }
    }
}
