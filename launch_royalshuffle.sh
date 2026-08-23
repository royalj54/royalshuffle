#!/bin/bash

source "$HOME/.config/royalshuffle/env"

cd /dmiles-randomizer || exit 1
source .venv/bin/activate
exec python ui.py
