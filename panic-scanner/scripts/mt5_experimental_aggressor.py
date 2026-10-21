"""Flag interpretation for experimental probes only; no operational stream changes."""

BUY = 32
SELL = 64
KNOWN_MASK = 2 | 4 | 8 | 16 | BUY | SELL


def aggressor_side(flags):
    if type(flags) is not int or flags < 0:
        raise ValueError("flags deve ser inteiro nao negativo")
    buy = bool(flags & BUY)
    sell = bool(flags & SELL)
    return "AMBIGUOUS" if buy == sell else "BUY" if buy else "SELL"


def unknown_flag_mask(flags):
    if type(flags) is not int or flags < 0:
        raise ValueError("flags deve ser inteiro nao negativo")
    return flags & ~KNOWN_MASK
