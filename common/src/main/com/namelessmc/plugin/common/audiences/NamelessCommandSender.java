package com.namelessmc.plugin.common.audiences;

import com.namelessmc.plugin.common.Permission;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.audience.ForwardingAudience;
import org.checkerframework.checker.nullness.qual.NonNull;

public abstract class NamelessCommandSender implements ForwardingAudience.Single {

	private final @NonNull Audience audience;

	public NamelessCommandSender(final @NonNull Audience audience) {
		this.audience = audience;
	}

	public abstract boolean hasPermission(Permission permission);

	@Override
	public @NonNull Audience audience() {
		return this.audience;
	}

}
