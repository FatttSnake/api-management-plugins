package com.example.filebox.db

import com.baomidou.mybatisplus.core.toolkit.reflect.IGenericTypeResolver
import com.baomidou.mybatisplus.core.toolkit.reflect.TypeParameterResolver

/**
 * Resolves mapper type arguments without Spring.
 *
 * MyBatis-Plus prefers Spring's `GenericTypeResolver`, but the gateway hides Spring from
 * plugins. Its probe still sees Spring, because it also inspects the application class loader,
 * so the later call fails; registering this resolver keeps generic resolution inside the
 * plugin.
 *
 * @author FatttSnake, fatttsnake@gmail.com
 * @since 1.0.0
 */
object MybatisTypeResolver : IGenericTypeResolver {
    /**
     * Resolves the type arguments a class supplies for an interface.
     *
     * @param clazz mapper interface
     * @param genericIfc generic interface, for example `BaseMapper`
     * @return resolved type arguments, or null when one cannot be resolved
     * @author FatttSnake, fatttsnake@gmail.com
     * @since 1.0.0
     */
    override fun resolveTypeArguments(clazz: Class<*>, genericIfc: Class<*>): Array<Class<*>>? {
        val values = TypeParameterResolver.resolveParameterValues(clazz)
        val arguments =
            genericIfc.typeParameters.map { parameter ->
                TypeParameterResolver.calculateParameterValue(values, parameter) as? Class<*> ?: return null
            }

        return arguments.toTypedArray()
    }
}
