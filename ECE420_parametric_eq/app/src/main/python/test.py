import os
import pandas as pd

def get_temperature_files(root_dir, temperature):
    files = []
    for subdir, _, filelist in os.walk(root_dir):
        for file in filelist:
            if 'OCV' in file and file.endswith(f'{temperature}C_Channel_1_Wb_1.csv'):
                files.append(os.path.join(subdir, file))
    return files

def extract_ocv_data(file_path):
    df = pd.read_csv(file_path)
    # Identify discharge and charge periods (C/25 current rate)
    discharge_start = df[df['Current (A)'] < 0].index[0]
    discharge_end = df[df['Current (A)'] == 0].index[0]
    charge_start = df[(df.index > discharge_end) & (df['Current (A)'] > 0)].index[0]
    charge_end = df[(df.index > charge_start) & (df['Current (A)'] == 0)].index[0]

    discharge_data = df.iloc[discharge_start:discharge_end+1]
    charge_data = df.iloc[charge_start:charge_end+1]

    return discharge_data, charge_data

def calculate_soc_soe(df):
    df['Ah'] = df['Current (A)'].cumsum() / 3600  # Convert to Ah
    df['Wh'] = (df['Voltage (V)'] * df['Current (A)']).cumsum() / 3600  # Convert to Wh
    total_ah = df['Ah'].iloc[-1]
    total_wh = df['Wh'].iloc[-1]
    df['SOC'] = df['Ah'] / total_ah * 100
    df['SOE'] = df['Wh'] / total_wh * 100
    return df

def compile_ocv_data(files):
    all_discharge_data = []
    all_charge_data = []
    
    for file in files:
        discharge_data, charge_data = extract_ocv_data(file)
        discharge_data = calculate_soc_soe(discharge_data)
        charge_data = calculate_soc_soe(charge_data)
        all_discharge_data.append(discharge_data)
        all_charge_data.append(charge_data)

    combined_discharge_data = pd.concat(all_discharge_data).groupby(level=0).mean()
    combined_charge_data = pd.concat(all_charge_data).groupby(level=0).mean()

    return combined_discharge_data, combined_charge_data

def main():
    root_dir = input("Enter the root directory of the data files: ")
    temperature = input("Enter the temperature to search for (e.g., 25): ")

    files = get_temperature_files(root_dir, temperature)
    if not files:
        print("No files found for the specified temperature.")
        return

    combined_discharge_data, combined_charge_data = compile_ocv_data(files)

    combined_discharge_data.to_csv(f'combined_discharge_data_{temperature}C.csv', index=False)
    combined_charge_data.to_csv(f'combined_charge_data_{temperature}C.csv', index=False)

    print("Combined data saved successfully.")

if __name__ == "__main__":
    main()
