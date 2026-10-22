package com.gadscape.cacheconv.core;

/**
 * One stage of the build. Steps read the inputs through {@link Ctx} (ctx.base, ctx.user, ctx.van211, ctx.old241,
 * ctx.out), write only through {@link Ctx#out}, record relocations in {@link Ctx#ids} and decisions in
 * {@link Ctx#report}. A step must be deterministic: iterate ids in ascending order and never depend on hash order,
 * time or the environment. The pipeline commits the output after every step.
 */
public interface Step {
    /** Short kebab-case name used by --only/--skip and in report.json. */
    String name();

    void run(Ctx ctx) throws Exception;
}
