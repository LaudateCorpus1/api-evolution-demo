package org.gradle.demo.api.evolution

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.runtime.EncodingGroovyMethods
import org.codehaus.groovy.tools.GroovyClass
import spock.lang.Specification

class ApiUpgradeManagerTest extends Specification {
    private ClassLoader originalClassLoader
    private GroovyClassLoader oldClassLoader
    private GroovyClassLoader newClassLoader

    private ApiUpgradeManager manager = new ApiUpgradeManager()

    def setup() {
        originalClassLoader = Thread.currentThread().contextClassLoader
        oldClassLoader = new GroovyClassLoader(originalClassLoader)
        newClassLoader = new GroovyClassLoader(originalClassLoader)
        Thread.currentThread().contextClassLoader = this.newClassLoader

        newClassLoader.parseClass """
            @groovy.transform.CompileStatic
            class Property<T> {
                private T value
                Property(T value) { set(value) }
                T get() { value }
                void set(T value) { this.value = value }
            }
        """
    }

    def cleanup() {
        Thread.currentThread().contextClassLoader = originalClassLoader
    }

    def "test"() {
        def serverClass = compileNew """
            @groovy.transform.CompileStatic
            class Server {
                private final Property<String> property = new Property<String>("init")
                Property<String> getString() { property }
            }
        """
        manager.matchProperty(serverClass, String, "string")
            .replaceWith(
                { server -> server.getString().get() },
                { server, value -> server.getString().set(value) }
            )
        manager.init()

        def oldServer = compileOld """
            @groovy.transform.CompileStatic
            class Server {
                private String value = "init"
                String getString() {
                    value
                }
                void setString(String value) {
                    this.value = value
                }
            }
        """
        def oldClient = compileAndUpgradeOld """
            @groovy.transform.CompileStatic
            class Client {
                public void test() {
                    println "Running test()"
                    def server = new Server()
                    assert server.getString() == "init"
                    server.setString("lajos")
                    assert server.getString() == "lajos"
                }
            }
        """
        when:
        oldClient.newInstance().test()
        then:
        noExceptionThrown()
    }

    class Property<T> {
        private T value

        Property(T value) {
            this.value = value
        }

        T get() {
            return value
        }

        void set(T value) {
            this.value = value
        }
    }

    private Class<?> compileNew(String script) {
        def newClass = compile(newClassLoader, script)
        return newClassLoader.loadClass(newClass.name)
    }

    private Class<?> compileAndUpgradeOld(String script) {
        def oldClass = compile(oldClassLoader, script)
        manager.implementReplacements(newClassLoader, oldClass.bytes)
        return newClassLoader.loadClass(oldClass.name)
    }

    private Class<?> compileOld(String script) {
        def oldClass = compile(oldClassLoader, script)
        return oldClassLoader.loadClass(oldClass.name)
    }

    private static GroovyClass compile(GroovyClassLoader classLoader, String script) {
        def compileUnit = new CompilationUnit()
        def sourceUnit = compileUnit.addSource("Script_" + EncodingGroovyMethods.md5(script) + ".groovy", script)
        compileUnit.classLoader = classLoader
        compileUnit.compile(Phases.CLASS_GENERATION)

        GroovyClass target = null
        for (Object compileClass : compileUnit.getClasses()) {
            GroovyClass groovyClass = (GroovyClass) compileClass
            classLoader.defineClass(groovyClass.name, groovyClass.bytes)
            if (groovyClass.getName().equals(sourceUnit.ast.mainClassName)) {
                target = groovyClass
                break
            }
        }

        if (target == null) {
            throw new IllegalStateException("Could not find compiled class")
        }

        return target
    }
}
