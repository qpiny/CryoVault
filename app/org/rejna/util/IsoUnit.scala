package org.rejna.util

object IsoUnit {
  implicit def apply(value: Long) = new IsoUnit(value)
  implicit def isoUnitToLong(u: IsoUnit) = u.value
  implicit def isoUnitToInt(u: IsoUnit) = u.value.toInt
}

class IsoUnit(val value: Long) {
  def kilo = new IsoUnit(value * 1000)
  def mega = new IsoUnit(value * 1000000)
  def giga = new IsoUnit(value * 1000000000)
  def tera = new IsoUnit(value * 1000000000000L)
  def peta = new IsoUnit(value * 1000000000000000L)
  def exa = new IsoUnit(value * 1000000000000000000L)

  def kibi = new IsoUnit(value * 1 << 10)
  def mebi = new IsoUnit(value * 1 << 20)
  def gibi = new IsoUnit(value * 1 << 30)
  def tebi = new IsoUnit(value * 1 << 40)
  def pebi = new IsoUnit(value * 1 << 50)
  def exbi = new IsoUnit(value * 1 << 60)

  override def toString =
    if (value < (1 << 10))
      value.toString
    else if (value < (1 << 20))
      "%.2fKi".format(value.toDouble / (1 << 10))
    else if (value < (1 << 30))
      "%.2fMi".format(value.toDouble / (1 << 20))
    else if (value < (1 << 40))
      "%.2fGi".format(value.toDouble / (1 << 30))
    else if (value < (1 << 50))
      "%.2fTi".format(value.toDouble / (1 << 40))
    else if (value < (1 << 60))
      "%.2fPi".format(value.toDouble / (1 << 50))
    else 
      "%.2fEi".format(value.toDouble / (1 << 60))
}
