"""Shared live contract selection and read-only terminal activation checks."""
import os
from pathlib import Path
import re
import time
import math


def configured_symbol():
    value = os.environ.get('MT5_SYMBOL')
    if value is None:
        settings = Path(__file__).resolve().parents[1] / 'src/main/resources/application.properties'
        match = re.search(r'^mt5\.symbol=([^\r\n]+)$',
                          settings.read_text(encoding='utf-8-sig'), re.MULTILINE)
        if not match:
            raise ValueError('Shared mt5.symbol default unavailable')
        value = match.group(1).strip()
        placeholder = re.fullmatch(r'\$\{MT5_SYMBOL:([A-Z0-9]+)\}',value)
        if placeholder:
            value = placeholder.group(1)
    value = value.strip().upper()
    if not re.fullmatch(r'WIN[A-Z][0-9]{2}', value):
        raise ValueError('Invalid MT5 live WIN contract')
    return value


def validate_live_symbol(mt5, symbol, now=time.time):
    terminal = mt5.terminal_info()
    if terminal is None or terminal.connected is not True:
        raise RuntimeError('MT5 terminal disconnected or unknown')
    if not mt5.symbol_select(symbol, True):
        raise RuntimeError(f'symbol_select({symbol}) failed: {mt5.last_error()}')
    info = mt5.symbol_info(symbol)
    if info is None or info.name != symbol or info.custom is not False:
        raise RuntimeError(f'Live symbol unavailable or custom: {symbol}')
    expiration = info.expiration_time
    if not isinstance(expiration, (int, float)) or not math.isfinite(expiration) or expiration <= now():
        raise RuntimeError(f'Live symbol expired or expiration unavailable: {symbol}')
    if info.trade_mode == 0:
        raise RuntimeError(f'Live symbol disabled: {symbol}')
    return info


def validate_live_tick(tick, symbol):
    if tick is None or int(tick.time_msc) <= 0 or not math.isfinite(float(tick.last)) or tick.last <= 0:
        raise RuntimeError(f'No usable initial tick for {symbol}')
