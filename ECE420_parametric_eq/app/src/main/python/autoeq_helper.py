# app/src/main/python/autoeq_helper.py

from autoeq.frequency_response import FrequencyResponse
from autoeq.constants import PEQ_CONFIGS

def run_autoeq(fr_txt_path, target_csv_path, output_eq_path):
    # 1) Load measured and target responses
    harman = FrequencyResponse.read_csv(target_csv_path)
    fr = FrequencyResponse.read_csv(fr_txt_path)

    # 2) Follow your desktop workflow
    fr.interpolate()
    fr.center()
    fr.compensate(harman)
    fr.smoothen()
    fr.equalize(concha_interference=False)

    # 3) Optimize 8-band + shelves
    config = dict(PEQ_CONFIGS['8_PEAKING_WITH_SHELVES'])
    peqs = fr.optimize_parametric_eq(config, 48000)

    # 4) Write out the EQ file (EQ APO format)
    fr.write_eqapo_parametric_eq(output_eq_path, peqs)

    # No return needed—file is on disk for Java to display