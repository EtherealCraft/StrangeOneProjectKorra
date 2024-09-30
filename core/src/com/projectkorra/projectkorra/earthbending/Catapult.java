package com.projectkorra.projectkorra.earthbending;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownBlockType;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.hooks.RegionProtectionHook;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.region.Towny;
import com.projectkorra.projectkorra.util.DamageHandler;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.util.ParticleEffect;

public class Catapult extends EarthAbility {

	private static boolean townyEnabled = RegionProtection.getActiveProtections().values().stream().anyMatch(hook -> hook instanceof Towny);

	private double stageTimeMult;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	private Location origin;
	private Location target;

	private int stage;
	private long stageStart;
	private boolean charging;
	private boolean activationHandled;
	private Vector up;
	private double angle;
	private double fullChargeAngle;
	private boolean cancelWithAngle;
	private BlockData bentBlockData;
	private BossBar chargeBar;
	private int timePerStage;
	private int timeForFullCharge;
	private long startTime;
	private boolean usingNewCatapult;
	private int maxStages;

	public Catapult(final Player player, final boolean sneak) {
		super(player);

		final Block b = player.getLocation().getBlock().getRelative(BlockFace.DOWN, 1);
		if (!(isEarth(b) || isSand(b) || isMetal(b))) {
			return;
		}

		this.bentBlockData = b.getBlockData();

		if (!this.bPlayer.canBend(this)) {
			return;
		}

		if (this.bPlayer.isAvatarState()) {
			this.cooldown = getConfig().getLong("Abilities.Avatar.AvatarState.Earth.Catapult.Cooldown");
		}

		this.charging = sneak;
		this.setFields();
		this.start();
	}

	private void setFields() {
		this.stageTimeMult = getConfig().getDouble("Abilities.Earth.Catapult.StageTimeMult");
		this.cooldown = getConfig().getLong("Abilities.Earth.Catapult.Cooldown");
		this.angle = Math.toRadians(getConfig().getDouble("Abilities.Earth.Catapult.Angle"));
		this.fullChargeAngle = Math.toRadians(getConfig().getDouble("Abilities.Earth.Catapult.FullChargeAngle"));
		this.cancelWithAngle = getConfig().getBoolean("Abilities.Earth.Catapult.CancelWithAngle");
		this.usingNewCatapult = getConfig().getBoolean("Abilities.Earth.Catapult.UseNewCatapult");
		this.activationHandled = false;
		this.stage = charging ? 2 : 1;
		this.stageStart = System.currentTimeMillis();
		this.up = new Vector(0, 1, 0);

		if (charging) {
			playWhoosh();
			if (usingNewCatapult) {
				this.chargeBar = Bukkit.getServer().createBossBar("Charging Catapult", BarColor.WHITE, BarStyle.SEGMENTED_6);
				this.chargeBar.setProgress(0);
			}
		}

		maxStages = usingNewCatapult ? 5 : 4;
		timePerStage = (int)(stageTimeMult * 1000);
		timeForFullCharge = timePerStage * 3;
		startTime = System.currentTimeMillis();
	}

	private void moveEarth(final Vector apply, final Vector direction) {
		List<Entity> launchEntities = new ArrayList<>();

		for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.origin, 2)) {
			if (entity.getEntityId() != this.player.getEntityId()) {
				if (townyEnabled) {
					TownBlock townBlock = TownyAPI.getInstance().getTownBlock(entity.getLocation());
					if (townBlock != null && !townBlock.getType().equals(TownBlockType.ARENA)) {
						continue;
					}
				}
				if (RegionProtection.isRegionProtected(this, entity.getLocation()) ||
						((entity instanceof Player) && Commands.invincible.contains((entity).getName()))) {
					continue;
				}
				launchEntities.add(entity);
			}
		}
		for (Entity la : launchEntities) {
			GeneralMethods.setVelocity(this, la, apply);
		}
		this.moveEarth(this.origin.clone().subtract(direction), direction, 3, false);
	}

	private boolean fullLauching = false;
	private int fullLaunchSteps = -1;
	private int maxFullLaunchSteps = 40;
	private int stuckTicks = 0;
	private Vector direction;
	Location beforeUpdateLocation;
	Location currentLocation;
	@Override
	public void progress() {
		if (!this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
			this.remove();
			return;
		}
		if (!fullLauching) {
			final Block b = this.player.getLocation().getBlock().getRelative(BlockFace.DOWN, 1);
			if (!(isEarth(b) || isSand(b) || isMetal(b))) {
				this.remove();
				return;
			}

			this.bentBlockData = b.getBlockData();
			Vector eyeDirection = this.player.getEyeLocation().getDirection();
			if (this.charging) {
				if (this.stage == maxStages || !this.player.isSneaking()) {
					this.charging = false;
					if (usingNewCatapult) {
						if (chargeBar.getProgress() >= 0.95) {
							stage = maxStages;
						}
						if (stage != maxStages) {
							chargeBar.removeAll();
						}
					}
				} else {
					long elapsedTime = System.currentTimeMillis() - startTime;
					if (usingNewCatapult) {
						chargeBar.setProgress(Math.min(1, (double) elapsedTime / timeForFullCharge));
					}
					double angleFromUp = this.up.angle(eyeDirection);
					if (usingNewCatapult) {
						updateBossBar(angleFromUp);
					}

					boolean goNextStage;
					if (usingNewCatapult) {
						goNextStage = System.currentTimeMillis() - this.stageStart >= timePerStage;
					} else {
						goNextStage = System.currentTimeMillis() - this.stageStart >= this.stageTimeMult * (this.stage - 1) * 1000;
					}

					if (goNextStage) {
						this.stage++;
						this.stageStart = System.currentTimeMillis();
						playWhoosh();
					}
				}
				return;
			}

			if (!this.activationHandled) {
				this.origin = this.player.getLocation().clone();
				direction = eyeDirection.clone().normalize();

				if (!this.bPlayer.canBend(this)) {
					this.activationHandled = true;
					this.remove();
					return;
				}
				this.activationHandled = true;
				this.bPlayer.addCooldown(this);
			}

			double checkAngle = !usingNewCatapult || stage <= 4 ? angle : fullChargeAngle;

			if (this.up.angle(eyeDirection) > checkAngle) {
				if (this.cancelWithAngle) {
					this.remove();
					return;
				}
				direction = this.up;
			}
		}

		if (fullLauching && fullLaunchSteps < maxFullLaunchSteps || !fullLauching && fullLaunchSteps == -1) {
			final Location tar = this.origin.clone().add(direction.clone().normalize().multiply(Math.min(4, this.stage) + 0.5));
			this.target = tar;
			double factor = fullLaunchSteps > maxFullLaunchSteps - 10 ? 1 - 2.0/(maxFullLaunchSteps + 2 - fullLaunchSteps) : 1;
			final Vector apply = this.target.clone().toVector().subtract(this.origin.clone().toVector()).multiply(factor);

			if (beforeUpdateLocation == null)
			{
				beforeUpdateLocation = player.getLocation();
			} else {
				currentLocation = player.getLocation();
				boolean stuckX, stuckY = false;

				if (((stuckX = beforeUpdateLocation.getX() == currentLocation.getX())
						|| (stuckY = beforeUpdateLocation.getY() == currentLocation.getY())
						|| beforeUpdateLocation.getZ() == currentLocation.getZ())
						&& fullLaunchSteps > 2) { // a couple of times this triggered at the beginning of a move, couldn't reproduce it. But this should prevent that.
					boolean hitSomething;
					if (stuckX) {
						hitSomething = !(player.getLocation().clone().add(1, 0, 0).getBlock().isPassable() && player.getLocation().clone().add(-1, 0, 0).getBlock().isPassable());
					} else if (stuckY) {
						hitSomething = !player.getLocation().clone().add(0, 2, 0).getBlock().isPassable();
					} else {
						hitSomething = !(player.getLocation().clone().add(0, 0, 1).getBlock().isPassable() && player.getLocation().clone().add(0, 0, -1).getBlock().isPassable());
					}

					if (hitSomething) {
						DamageHandler.damageEntity(player, 2, this, true);
						this.remove();
						return;
					}

				} else {
					beforeUpdateLocation = player.getLocation();
					stuckTicks = 0;
				}
			}

			GeneralMethods.setVelocity(this, this.player, apply);

			fullLaunchSteps++;
			if (!fullLauching) this.moveEarth(apply, direction);
			if (direction.equals(this.up) || stage <= 3 || !usingNewCatapult) {
				this.remove();
				return;
			}
			if (!fullLauching) fullLauching = true;

			chargeBar.setProgress(1- (double)fullLaunchSteps / maxFullLaunchSteps);
		} else {
			this.remove();
		}
	}

	private void updateBossBar(double angleFromUp) {
		boolean angleTooLowFullCharge = angleFromUp > this.fullChargeAngle;

		ChatColor statusColor;
		if (angleTooLowFullCharge) {
			statusColor = ChatColor.YELLOW;
			chargeBar.setColor(BarColor.YELLOW);
		} else {
			statusColor = ChatColor.GREEN;
			chargeBar.setColor(BarColor.GREEN);
		}
		chargeBar.setTitle(statusColor + "Full charge direction > " + (angleTooLowFullCharge ? "UP" : "FORWARD"));

		if (stage == 2 && System.currentTimeMillis() - this.stageStart > 300) {
			chargeBar.addPlayer(player);
		}
	}

	private void playWhoosh() {
		final Random random = new Random();
		ParticleEffect.BLOCK_DUST.display(this.player.getLocation(), 15, random.nextFloat(), random.nextFloat(), random.nextFloat(), this.bentBlockData);
		ParticleEffect.BLOCK_DUST.display(this.player.getLocation().add(0, 0.5, 0), 10, random.nextFloat(), random.nextFloat(), random.nextFloat(), this.bentBlockData);
		this.player.getWorld().playEffect(this.player.getLocation(), Effect.GHAST_SHOOT, 0, 10);
	}

	@Override
	public String getName() {
		return "Catapult";
	}

	@Override
	public Location getLocation() {
		if (this.player != null) {
			return this.player.getLocation();
		}
		return null;
	}

	@Override
	public void remove() {
		if (chargeBar != null) {
			chargeBar.removeAll();
		}
		super.remove();
	}

	@Override
	public long getCooldown() {
		return this.cooldown;
	}

	@Override
	public boolean isSneakAbility() {
		return true;
	}

	@Override
	public boolean isHarmlessAbility() {
		return false;
	}

	public Location getOrigin() {
		return this.origin;
	}

	public void setOrigin(final Location origin) {
		this.origin = origin;
	}

	public Location getTarget() {
		return this.target;
	}

	public void setTarget(final Location target) {
		this.target = target;
	}

	public void setCooldown(final long cooldown) {
		this.cooldown = cooldown;
	}
}
