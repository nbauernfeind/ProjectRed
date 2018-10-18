package mrtjp.projectred.util

import gregtech.api.GregTech_API
import gregtech.api.util.{GT_ModHandler, GT_Utility}
import mrtjp.projectred.api.IScrewdriver
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemStack
import net.minecraft.world.World

object ToolUtil {
  def isScrewdriver(held: ItemStack): Boolean =
  {
    if (held == null) return false

    held.getItem match {
      case screwdriver: IScrewdriver => true
      case _ if GT_Utility.isStackInList(held, GregTech_API.sScrewdriverList) => true
      case _ => false
    }
  }

  def tryToUseScrewdriver(world: World, player: EntityPlayer, held: ItemStack, x: Int, y: Int, z: Int): Boolean =
  {
    if (held == null) return false

    val used = held.getItem match
    {
      case screwdriver: IScrewdriver if screwdriver.canUse(player, held) =>
        if (!world.isRemote) screwdriver.damageScrewdriver(player, held)
        true
      case _ if GT_Utility.isStackInList(held, GregTech_API.sScrewdriverList) =>
        world.isRemote || GT_ModHandler.damageOrDechargeItem(held, 1, 200, player)
      case _ => false
    }

    if (!world.isRemote && used)
    {
      GT_Utility.sendSoundToPlayers(world, GregTech_API.sSoundList.get(100), 1.0F, -1, x, y, z)
    }

    used
  }
}
