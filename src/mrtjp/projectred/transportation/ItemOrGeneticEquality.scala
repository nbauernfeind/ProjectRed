/*
 * Copyright (c) 2018.
 * All rights reserved.
 */
package mrtjp.projectred.transportation

import forestry.core.genetics.ItemGE
import mrtjp.core.item.{AppliedItemEquality, ItemEquality, ItemKey}

class ItemOrGeneticEquality extends ItemEquality {
  override def apply(key: ItemKey): AppliedItemEquality = new AppliedItemOrGeneticEquality(key)

  override def matches(key1: ItemKey, key2: ItemKey): Boolean =
  {
    if (super.matches(key1, key2)) return true
    if (key1 == null || key2 == null) return false
    if (key1.itemID != key2.itemID) return false

    key1.item match
    {
      case ge1: ItemGE => key2.item match
      {
        case ge2: ItemGE =>
          if (ge1.getIndividual(key1.testStack).isGeneticEqual(ge2.getIndividual(key2.testStack)))
            return true;
        case _ =>
      }
    }

    false
  }
}

class AppliedItemOrGeneticEquality(_key: ItemKey) extends AppliedItemEquality(_key) {
  override def matches(key2: ItemKey): Boolean =
  {
    if (super.matches(key2)) return true
    if (key == null || key2 == null) return false
    if (key.itemID != key2.itemID) return false

    key.item match
    {
      case ge1: ItemGE => key2.item match
      {
        case ge2: ItemGE =>
          if (ge1.getIndividual(key.testStack).isGeneticEqual(ge2.getIndividual(key2.testStack)))
            return true;
        case _ =>
      }
    }

    false
  }
}
