def roundToNDecimals(number: Double, n: Int): Double = {
    val bd = BigDecimal(number)
    bd.setScale(n, BigDecimal.RoundingMode.HALF_UP).toDouble
}

class Utilities {

}
